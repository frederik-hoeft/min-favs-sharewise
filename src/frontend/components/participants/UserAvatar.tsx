import { Avatar } from '@mantine/core';
import { userAvatarColor, userInitials } from './user-presentation';

/** A pure generated avatar; the accompanying identity text conveys the person's identity. */
export function UserAvatar({ userId, displayName, size = 'sm' }: { userId: string; displayName?: string; size?: string }) {
  return <Avatar aria-hidden="true" size={size} radius="xl" variant="filled" color={userAvatarColor(userId)}
    styles={{ root: { flexShrink: 0 }, placeholder: { color: '#fff', fontWeight: 700 } }}>
    {userInitials(displayName)}
  </Avatar>;
}
