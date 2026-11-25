import { ThemeIcon } from "@mantine/core";
import { UsersThreeIcon } from "@phosphor-icons/react";

/** Placeholder until group images have their own feature slice. */
export function GroupAvatar({ size = "lg" }: { size?: string | number }) {
  return <ThemeIcon size={size} variant="light" radius="md" aria-hidden="true"><UsersThreeIcon size="60%" /></ThemeIcon>;
}
