import type { ReactNode } from "react";
import { RequireAuthentication } from "@/components/auth/RequireAuthentication";

/** Only account operations are protected; the demonstration workspace remains public. */
export default function AccountLayout({ children }: Readonly<{ children: ReactNode }>) {
  return <RequireAuthentication>{children}</RequireAuthentication>;
}
