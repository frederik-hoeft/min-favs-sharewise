import type { ProfileResolution } from '@/core/identity';

// Identity accents are deliberately distinct from action/feedback colors. White initials
// retain WCAG AA text contrast on these fills in either application color scheme.
const palette = ['#414a9e', '#733b82', '#9b3655', '#825027', '#534c68', '#3d587d', '#71416b', '#51576a'] as const;

function hexadecimal(userId: string): string {
  return userId.replaceAll('-', '').toLowerCase();
}

/** Stable FNV-1a over the canonical UUID hexadecimal characters. */
export function userAvatarColor(userId: string): string {
  let hash = 0x811c9dc5;
  for (const character of hexadecimal(userId)) hash = Math.imul(hash ^ character.charCodeAt(0), 0x01000193);
  return palette[(hash >>> 0) % palette.length];
}

export function shortUserId(userId: string): string {
  return `#${hexadecimal(userId).slice(-8)}`;
}

/** Resolve collisions for the entire distinct user set, not just equally named users. */
export function disambiguateUserIds(userIds: readonly string[]): ReadonlyMap<string, string> {
  const ids = [...new Set(userIds)];
  const reversed = ids.map(id => ({ id, hex: hexadecimal(id).split('').reverse().join('') }))
    .sort((left, right) => left.hex < right.hex ? -1 : left.hex > right.hex ? 1 : 0);
  function commonPrefix(left: string, right: string): number {
    let length = 0;
    while (length < left.length && left[length] === right[length]) length++;
    return length;
  }
  return new Map(reversed.map(({ id, hex }, index) => {
    const needed = Math.max(8, 1 + commonPrefix(hex, reversed[index - 1]?.hex ?? ''),
      1 + commonPrefix(hex, reversed[index + 1]?.hex ?? ''));
    return [id, `#${hexadecimal(id).slice(-needed)}`];
  }));
}

export function userInitials(name?: string): string {
  const words = name?.trim().split(/\s+/u).filter(Boolean) ?? [];
  if (!words.length) return '?';
  const first = Array.from(words[0])[0];
  const last = words.length > 1 ? Array.from(words[words.length - 1])[0] : '';
  return Array.from((first + last).toUpperCase()).slice(0, 2).join('');
}

export function userDisplayLabel(profile?: ProfileResolution): string {
  if (!profile) return 'Resolving name…';
  if (profile.status === 'missing') return 'Unknown user';
  return profile.displayName ?? 'Name unavailable';
}
