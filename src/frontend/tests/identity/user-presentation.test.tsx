import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { disambiguateUserIds, shortUserId, userAvatarColor, userInitials, memberIdentityLabel } from '@/components/participants/user-presentation';
import { UserIdentity } from '@/components/participants/UserIdentity';
import { TestProviders } from '../support/TestProviders';

const one = '0193a5c4-7e1f-7c2a-9b3d-0000a8d291cf';
const two = '8193a5c4-7e1f-7c2a-9b3d-0000a8d291cf';
const three = '0193a5c4-7e1f-7c2a-9b3d-0001a8d291cf';

describe('deterministic identity presentation', () => {
  it('extends colliding suffixes until unique, including full 32-character fallback', () => {
    const ids = disambiguateUserIds([one, two, three, one]);
    expect(ids.size).toBe(3);
    expect(ids.get(one)).toBe('#0193a5c47e1f7c2a9b3d0000a8d291cf');
    expect(ids.get(two)).toBe('#8193a5c47e1f7c2a9b3d0000a8d291cf');
    expect(ids.get(three)).toBe('#1a8d291cf');
    expect(disambiguateUserIds([three, two, one])).toEqual(ids);
    expect(shortUserId(one)).toBe('#a8d291cf');
  });

  it('uses stable colors and initials, independent of names and UUID casing', () => {
    expect(userAvatarColor(one)).toBe(userAvatarColor(one.toUpperCase()));
    expect(userAvatarColor(one)).toBe('hsl(135, 49%, 34%)');
    expect(userAvatarColor(two)).toBe('hsl(191, 68%, 34%)');
    const collision = '000013f4-7e1f-7c2a-9b3d-0000a8d291cf';
    expect(userAvatarColor(collision)).toBe(userAvatarColor(one));
    expect(disambiguateUserIds([one, collision]).get(one)).not.toBe(disambiguateUserIds([one, collision]).get(collision));
    expect(userInitials('  Thomas Müller  ')).toBe('TM');
    expect(userInitials('🦊')).toBe('🦊');
    expect(userInitials(undefined)).toBe('?');
  });

  it('generates varied HSL colors with at least AA contrast against white initials', () => {
    // Independently calculate contrast from the CSS HSL components (sRGB).
    function contrast(color: string): number {
      const match = /^hsl\((\d+), (\d+)%, (\d+)%\)$/.exec(color);
      if (!match) throw new Error(`Unexpected avatar color: ${color}`);
      const [, h, s, l] = match.map(Number);
      const lightness = l / 100;
      const amplitude = s / 100 * Math.min(lightness, 1 - lightness);
      const linearChannel = (offset: number): number => {
        const k = (offset + h / 30) % 12;
        const value = lightness - amplitude * Math.max(-1, Math.min(k - 3, 9 - k, 1));
        return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4;
      };
      return 1.05 / (0.05 + 0.2126 * linearChannel(0) + 0.7152 * linearChannel(8) + 0.0722 * linearChannel(4));
    }
    const colors = new Set(Array.from({ length: 4096 }, (_, index) =>
      userAvatarColor(`${index.toString(16).padStart(8, '0')}${one.slice(8)}`)));
    expect(colors.size).toBeGreaterThan(3000);
    for (const color of colors) expect(contrast(color)).toBeGreaterThanOrEqual(4.5);
  });

  it('keeps the complete name and secondary identity available with a compact label and accessory', () => {
    const name = 'A'.repeat(80);
    const { rerender } = render(<TestProviders><UserIdentity userId={one} profile={{ status: 'resolved', displayName: name }}
      own nameAccessory={<span>Admin</span>} /></TestProviders>);
    const label = screen.getByText(name);
    expect(label).toHaveAttribute('title', name);
    expect(label.parentElement?.parentElement).toContainElement(screen.getByText('Admin'));
    expect(screen.getByText('(You)')).toBeVisible();
    expect(screen.getByText('#a8d291cf')).toBeVisible();
    const compactClass = label.className;
    rerender(<TestProviders><UserIdentity userId={one} profile={{ status: 'resolved', displayName: name }} wrapName /></TestProviders>);
    expect(screen.getByText(name).className).not.toBe(compactClass);
  });

  it('isolates accessible names and IDs even if a legacy name contains embedded bidi controls', () => {
    expect(memberIdentityLabel({ status: 'resolved', displayName: 'שלום\u2069\u202eabc' }, '#a8d291cf'))
      .toBe('\u2068שלוםabc\u2069 (\u2066#a8d291cf\u2069)');
  });

  it('presents untrusted text with directional isolation, not raw HTML or color-only cues', () => {
    const name = '<script>שלום</script>';
    render(<TestProviders><UserIdentity userId={one} profile={{ status: 'resolved', displayName: name }} own /></TestProviders>);
    expect(screen.getByText(name).tagName).toBe('BDI');
    expect(screen.getByText('#a8d291cf')).toHaveAttribute('dir', 'ltr');
    expect(screen.getByText('(You)')).toBeVisible();
  });
});
