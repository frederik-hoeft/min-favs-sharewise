import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { disambiguateUserIds, shortUserId, userAvatarColor, userInitials } from '@/components/participants/user-presentation';
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
    expect(userAvatarColor(one)).toBe('#51576a');
    expect(userAvatarColor(two)).toBe(userAvatarColor(one));
    expect(userInitials('  Thomas Müller  ')).toBe('TM');
    expect(userInitials('🦊')).toBe('🦊');
    expect(userInitials(undefined)).toBe('?');
  });

  it('presents untrusted text with directional isolation, not raw HTML or color-only cues', () => {
    const name = '<script>שלום</script>';
    render(<TestProviders><UserIdentity userId={one} profile={{ status: 'resolved', displayName: name }} own /></TestProviders>);
    expect(screen.getByText(name).tagName).toBe('BDI');
    expect(screen.getByText('#a8d291cf')).toHaveAttribute('dir', 'ltr');
    expect(screen.getByText('(You)')).toBeVisible();
  });
});
