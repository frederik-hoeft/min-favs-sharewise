"use client";

import { createContext, useContext, useEffect, useState, useSyncExternalStore, Suspense, type ReactNode } from "react";
import { usePathname, useSearchParams } from "next/navigation";
import { isInvitationToken } from "@/core/groups";
import { INVITE_PATH } from "@/lib/navigation/invitation-routes";
import { useIdentity } from "./identity-provider";

/** A root-owned, tab-local capability. Subscribers see only a revision, never the bearer. */
export class InvitationContinuation {
  #token: string | null = null;
  #lifetime: number | undefined;
  #revision = 0;
  #listeners = new Set<() => void>();

  readonly subscribe = (listener: () => void) => {
    this.#listeners.add(listener);
    return () => { this.#listeners.delete(listener); };
  };
  readonly getSnapshot = () => this.#revision;

  get pending(): boolean { return this.#token !== null; }

  capture(fragment: string) {
    this.#token = isInvitationToken(fragment) ? fragment : null;
    this.#lifetime = undefined;
    this.changed();
  }

  bind(lifetime: number | undefined): void {
    if (!this.pending || lifetime === undefined) return;
    if (this.#lifetime !== undefined && this.#lifetime !== lifetime) this.clear();
    else this.#lifetime = lifetime;
  }

  /** The pending anonymous continuation may bind once, but never move to a replacement session. */
  forSession(lifetime: number | undefined): string | null {
    if (lifetime === undefined || lifetime !== this.#lifetime) return null;
    return this.#token;
  }

  invalidateIfChanged(lifetime: number | undefined) {
    if (this.#lifetime !== undefined && this.#lifetime !== lifetime) this.clear();
  }

  clear() {
    if (!this.pending) return;
    this.#token = null;
    this.#lifetime = undefined;
    this.changed();
  }

  private changed() {
    this.#revision += 1;
    for (const listener of this.#listeners) listener();
  }
}

const Context = createContext<InvitationContinuation | null>(null);
const noSubscribe = () => () => {};
const zero = () => 0;

/** Keep continuation only on the invitation and its explicit authentication detour. */
export function InvitationContinuationProvider({ children }: { children: ReactNode }) {
  const [continuation] = useState(() => new InvitationContinuation());
  const { session } = useIdentity();
  useEffect(() => session.subscribe(() => {
    const state = session.getSnapshot();
    if (state.status === "context-changed") continuation.clear();
    else {
      const lifetime = session.getAuthenticatedLifetime();
      continuation.invalidateIfChanged(lifetime);
      if (state.status === "authenticated") continuation.bind(lifetime);
    }
  }), [continuation, session]);
  return <Context.Provider value={continuation}>
    <Suspense fallback={null}><InvitationNavigationObserver continuation={continuation} /></Suspense>
    {children}
  </Context.Provider>;
}

/** Query observation is isolated so static pages do not require client rendering. */
function InvitationNavigationObserver({ continuation }: { continuation: InvitationContinuation }) {
  const pathname = usePathname();
  const next = useSearchParams()?.get("next");
  useEffect(() => {
    const authDetour = (pathname === "/auth/login" || pathname === "/auth/register") && next === INVITE_PATH;
    if (pathname !== INVITE_PATH && !authDetour) continuation.clear();
  }, [continuation, pathname, next]);
  return null;
}

/** The optional context keeps standalone authentication form rendering independent of invitation flows. */
export function useInvitationContinuation() {
  const continuation = useContext(Context);
  const revision = useSyncExternalStore(continuation?.subscribe ?? noSubscribe, continuation?.getSnapshot ?? zero, zero);
  return { continuation, revision, pending: continuation?.pending ?? false };
}
