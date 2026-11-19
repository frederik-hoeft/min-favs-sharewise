import type { ReactNode } from "react";
import type { Metadata } from "next";
import { ApplicationShell } from "./_components/ApplicationShell";

export const metadata: Metadata = {
  title: { default: "Workspace | ShareWise", template: "%s | ShareWise" },
  description: "ShareWise application workspace",
};

/** The /app route subtree shares one navigable shell; public pages use their own AppShell. */
export default function AppLayout({ children }: Readonly<{ children: ReactNode }>) {
  return <ApplicationShell>{children}</ApplicationShell>;
}
