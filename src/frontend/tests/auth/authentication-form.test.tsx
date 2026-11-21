import { render, screen, fireEvent, within, waitFor, act } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import axe from "axe-core";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthenticationForm } from "@/app/(auth)/_components/AuthenticationForm";
import AuthLayout from "@/app/(auth)/layout";
import { IdentityProvider } from "@/app/identity-provider";
import { createIdentityRuntime } from "@/core/identity";
import { SessionNotice } from "@/components/auth/SessionNotice";
import { TestProviders } from "../support/TestProviders";
import { ControlledLocks, authenticationResponse, deferred, jsonResponse, problemResponse } from "../identity/support";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));

const activeRuntimes: Array<ReturnType<typeof createIdentityRuntime>> = [];
function renderAuth(
  mode: 'login' | 'register',
  fetchImpl: typeof fetch = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
    ? jsonResponse({ token: 'csrf' })
    : jsonResponse(authenticationResponse(), mode === 'register' ? 201 : 200)) as typeof fetch,
  locks: () => LockManager | undefined = () => new ControlledLocks().manager,
) {
  const runtime = createIdentityRuntime({ fetchImpl, locks });
  activeRuntimes.push(runtime);
  const view = render(
    <TestProviders><IdentityProvider runtime={runtime}>
      <AuthLayout><AuthenticationForm mode={mode} /></AuthLayout>
    </IdentityProvider></TestProviders>,
  );
  return { runtime, fetchImpl, ...view };
}

async function enterCredentials(user: ReturnType<typeof userEvent.setup>, password = 'correct horse battery staple') {
  await user.type(screen.getByRole('textbox', { name: /email address/i }), 'person@example.com');
  await user.type(screen.getByLabelText(/^Password/), password);
}

afterEach(() => {
  for (const runtime of activeRuntimes.splice(0)) runtime.dispose();
  replace.mockReset();
});

describe('registration and login presentation', () => {
  it.each([['login', 'Sign in', '/auth/register'], ['register', 'Create account', '/auth/login']] as const)(
    'renders the %s form with accessible links and a clickable home brand', (mode, formName, otherRoute) => {
      renderAuth(mode);
      const form = screen.getByRole('form', { name: formName });
      expect(within(form).getByRole('textbox', { name: /email address/i })).toHaveAttribute('autocomplete', 'email');
      expect(within(form).getByRole('textbox', { name: /email address/i })).toHaveAttribute('type', 'email');
      expect(within(form).getByRole('textbox', { name: /email address/i })).toHaveAttribute('inputmode', 'email');
      if (mode === 'register') {
        expect(screen.getByText('Use between 8 and 256 characters.')).toBeVisible();
      }
      expect(screen.queryByText(/UTF-8 bytes/i)).not.toBeInTheDocument();
      expect(within(form).getByLabelText(/^Password/)).toHaveAttribute(
        'autocomplete', mode === 'register' ? 'new-password' : 'current-password',
      );
      expect(within(form).getByRole('button', { name: formName })).toBeVisible();
      expect(screen.getByRole('link', { name: 'ShareWise home' })).toHaveAttribute('href', '/');
      expect(screen.getByRole('link', { name: /learn more about sharewise/i })).toHaveAttribute('href', '/#product');
      expect(screen.getByRole('link', { name: mode === 'register' ? 'Sign in' : 'Create an account' }))
        .toHaveAttribute('href', otherRoute);
      expect(screen.getByRole('main')).toBeInTheDocument();
      expect(screen.queryByRole('link', { name: /back to home/i })).not.toBeInTheDocument();
    },
  );

  it('focuses invalid registration fields and prevents any HTTP traffic', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn();
    renderAuth('register', fetchImpl as typeof fetch);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(screen.getByRole('textbox', { name: /email address/i })).toHaveFocus();
    expect(screen.getByText('Enter your email address.')).toBeVisible();
    expect(fetchImpl).not.toHaveBeenCalled();
    await enterCredentials(user, 'short');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(screen.getByLabelText(/^Password/)).toHaveFocus();
    const passwordField = screen.getByLabelText(/^Password/);
    const guidance = screen.getByText('Use between 8 and 256 characters.');
    const passwordError = screen.getByText('Password is too short.');
    expect(guidance).toBeVisible();
    expect(passwordError).toBeVisible();
    // Guidance stays above the input on validation failure; only the separate
    // error appears below it, so the input does not jump between positions.
    expect(guidance.compareDocumentPosition(passwordField) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(passwordField.compareDocumentPosition(passwordError) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('trims email but sends passwords unchanged, then navigates client-side after registration', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/csrf')) return jsonResponse({ token: 'fresh' });
      expect(JSON.parse(String(init?.body))).toEqual({ email: 'person@example.com', password: '  correct horse battery staple  ' });
      return jsonResponse(authenticationResponse(), 201);
    });
    const { runtime } = renderAuth('register', fetchImpl as typeof fetch);
    fireEvent.change(screen.getByRole('textbox', { name: /email address/i }), { target: { value: '  person@example.com  ' } });
    await user.type(screen.getByLabelText(/^Password/), '  correct horse battery staple  ');
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app'));
    expect(runtime.session.getSnapshot().status).toBe('authenticated');
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it('allows an existing shorter password on login and supports keyboard submission', async () => {
    const user = userEvent.setup();
    renderAuth('login');
    await enterCredentials(user, 'short');
    await user.keyboard('{Enter}');
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app'));
  });

  it('focuses a duplicate registration email and supports retry without stale field errors', async () => {
    const user = userEvent.setup();
    let posts = 0;
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
      ? jsonResponse({ token: 'fresh' })
      : ++posts === 1 ? problemResponse(409, 'email_unavailable') : jsonResponse(authenticationResponse(), 201));
    renderAuth('register', fetchImpl as typeof fetch);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('This email address is unavailable.')).toBeVisible();
    expect(screen.getByRole('textbox', { name: /email address/i })).toHaveFocus();
    await user.clear(screen.getByRole('textbox', { name: /email address/i }));
    await user.type(screen.getByRole('textbox', { name: /email address/i }), 'different@example.org');
    expect(screen.queryByText('This email address is unavailable.')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app'));
  });

  it('maps field-level server validation and focuses its first relevant field', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
      ? jsonResponse({ token: 'fresh' })
      : problemResponse(400, 'validation_failed', [
        { field: 'password', code: 'password_too_long', message: 'Password is too long.' },
      ]));
    renderAuth('register', fetchImpl as typeof fetch);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Password is too long.')).toBeVisible();
    expect(screen.getByLabelText(/^Password/)).toHaveFocus();
    expect(replace).not.toHaveBeenCalled();
  });

  it('does not reveal account state on 401 and announces non-field errors', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
      ? jsonResponse({ token: 'fresh' })
      : problemResponse(401, 'account_locked'));
    renderAuth('login', fetchImpl as typeof fetch);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent("We couldn't sign you in");
    expect(alert).not.toHaveTextContent(/locked|disabled|unknown/i);
    expect(alert.getAttribute('style')).toContain('background-color: var(--sw-danger-surface)');
    expect(alert.closest('[tabindex="-1"]')).toHaveFocus();
    expect(replace).not.toHaveBeenCalled();
  });

  it('fails closed without Web Locks and does not send credentials', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn();
    renderAuth('login', fetchImpl as typeof fetch, () => undefined);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Web Locks');
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it('maps network failures to non-sensitive feedback and remains usable', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn(async () => { throw new TypeError('private network diagnostic'); });
    renderAuth('login', fetchImpl as typeof fetch);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/check your connection/i);
    expect(alert).not.toHaveTextContent(/private network diagnostic/i);
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });

  it('treats a lost registration POST response as uncertain and offers sign-in recovery', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      if (String(input).endsWith('/csrf')) return jsonResponse({ token: 'fresh' });
      throw new TypeError('private network diagnostic');
    });
    renderAuth('register', fetchImpl as typeof fetch);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/sign.?in/i);
    expect(alert).not.toHaveTextContent(/check your connection/i);
    expect(alert).not.toHaveTextContent(/private network diagnostic/i);
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/auth/login');
  });

  it('ignores malformed server field errors instead of throwing during error display', async () => {
    const user = userEvent.setup();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
      ? jsonResponse({ token: 'fresh' })
      : problemResponse(400, 'validation_failed', [null, 42, {}, {
        field: 'email', code: 'invalid', message: 'Invalid email on server.',
      }]));
    renderAuth('register', fetchImpl as typeof fetch);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Create account' }));
    expect(await screen.findByText('Invalid email on server.')).toBeVisible();
  });

  it('prevents duplicate submission while waiting on the CSRF request', async () => {
    const user = userEvent.setup();
    const csrf = deferred<Response>();
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => String(input).endsWith('/csrf')
      ? csrf.promise : jsonResponse(authenticationResponse()));
    renderAuth('login', fetchImpl as typeof fetch);
    await enterCredentials(user);
    await user.click(screen.getByRole('button', { name: 'Sign in' }));
    await waitFor(() => expect(fetchImpl).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('button', { name: /sign in/i })).toBeDisabled();
    csrf.resolve(jsonResponse({ token: 'fresh' }));
    await waitFor(() => expect(replace).toHaveBeenCalledWith('/app'));
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it.each(['login', 'register'] as const)('%s has no axe-detectable structural accessibility violations', async (mode) => {
    const { container } = renderAuth(mode);
    const result = await axe.run(container, { rules: { 'color-contrast': { enabled: false } } });
    expect(result.violations).toEqual([]);
  });

  it('makes reauthentication explicit in the demo workspace and reflects the memory-only session', () => {
    const fetchImpl = vi.fn();
    const runtime = createIdentityRuntime({ fetchImpl: fetchImpl as typeof fetch });
    activeRuntimes.push(runtime);
    const view = render(<TestProviders><IdentityProvider runtime={runtime}><SessionNotice /></IdentityProvider></TestProviders>);
    expect(screen.getByText(/no active sign-in in this tab/i)).toBeVisible();
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/auth/login');
    act(() => runtime.session.accept(authenticationResponse()));
    expect(screen.getByText(/temporary browser session is active/i)).toBeVisible();
    act(() => runtime.session.invalidate('expired'));
    expect(screen.getByText(/session has expired/i)).toBeVisible();
    view.unmount();
    const fresh = createIdentityRuntime();
    activeRuntimes.push(fresh);
    render(<TestProviders><IdentityProvider runtime={fresh}><SessionNotice /></IdentityProvider></TestProviders>);
    expect(screen.getByText(/no active sign-in in this tab/i)).toBeVisible();
  });
});
