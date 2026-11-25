import type { ReactNode } from "react";
import type { Metadata } from "next";
import { RequireAuthentication } from "@/components/auth/RequireAuthentication";

export const metadata: Metadata = {
  title: { default: "Workspace | ShareWise", template: "%s | ShareWise" },
  description: "ShareWise application workspace",
};

/** Every workspace route requires an active sign-in; public and auth routes use separate layouts. */
export default function AppLayout({ children }: Readonly<{ children: ReactNode }>) {
  return <RequireAuthentication>{children}</RequireAuthentication>;
}
