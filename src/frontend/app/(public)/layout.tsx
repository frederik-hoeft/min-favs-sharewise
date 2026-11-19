import { AppShell, AppShellMain } from "@mantine/core";
import type { ReactNode } from "react";
import { ShellHeader } from "@/components/navigation/ShellHeader";
import { PublicNavigation } from "./_components/PublicNavigation";
import { PublicFooter } from "./_components/PublicFooter";
import { SkipLink } from "@/components/navigation/SkipLink";

/** Public routes own their AppShell configuration; authenticated routes use a separate layout. */
export default function PublicLayout({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <>
      <SkipLink href="#main" />
      <AppShell padding={0} withBorder={false} header={{ height: { base: "5rem", "38.125rem": "6rem" } }}>
        <ShellHeader><PublicNavigation /></ShellHeader>
        <AppShellMain id="main">{children}</AppShellMain>
        <PublicFooter />
      </AppShell>
    </>
  );
}
