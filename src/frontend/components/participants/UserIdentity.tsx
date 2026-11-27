import type { ReactNode } from 'react';
import { Group, Stack, Text } from '@mantine/core';
import type { ProfileResolution } from '@/core/identity';
import { UserAvatar } from './UserAvatar';
import { shortUserId, userDisplayLabel } from './user-presentation';
import styles from './UserIdentity.module.scss';

export interface UserIdentityProps {
  userId: string;
  profile?: ProfileResolution;
  secondaryId?: string;
  own?: boolean;
  showSecondaryId?: boolean;
  children?: ReactNode;
}

/** Use the same contextual secondary ID in a list and the corresponding confirmation. */
export function UserIdentity({ userId, profile, secondaryId = shortUserId(userId), own = false, showSecondaryId = true, children }: UserIdentityProps) {
  const name = profile?.status === 'resolved' || profile?.status === 'unavailable' ? profile.displayName : undefined;
  return <Group wrap="nowrap" gap="sm" align="flex-start" className={styles.identity}>
    <UserAvatar userId={userId} displayName={name} />
    <Stack gap={2} className={styles.label}>
      <Text size="sm" fw={600} className={styles.name}><bdi>{userDisplayLabel(profile)}</bdi>{own ? ' (You)' : ''}</Text>
      {showSecondaryId && <Text size="xs" c="dimmed" className={styles.identifier} title={userId}><bdi dir="ltr">{secondaryId}</bdi></Text>}
      {profile?.status === 'unavailable' && name !== undefined && <Text size="xs" c="dimmed">Name may be outdated</Text>}
      {children}
    </Stack>
  </Group>;
}
