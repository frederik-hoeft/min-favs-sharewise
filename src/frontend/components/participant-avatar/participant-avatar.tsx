import { Avatar } from "@mantine/core";

/** Shared participant identity treatment; Mantine owns sizing and color-scheme adaptation. */
const tintColors = {
  green: "forest",
  peach: "clay",
  lilac: "grape",
  blue: "blue",
} as const;

export type ParticipantTint = keyof typeof tintColors;

export function ParticipantAvatar({
  name,
  initials,
  tint,
  size = 32,
}: {
  name: string;
  initials: string;
  tint: ParticipantTint;
  size?: number;
}) {
  return (
    <Avatar color={tintColors[tint]} variant="light" radius="xl" size={size} title={name}>
      {initials[0]}
    </Avatar>
  );
}
