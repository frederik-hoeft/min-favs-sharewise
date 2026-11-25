import type { ReactNode } from "react";
import { ApplicationShell } from "@/app/(app)/app/_components/ApplicationShell";

export default function StandardApplicationLayout({ children }: Readonly<{ children: ReactNode }>) {
  return <ApplicationShell>{children}</ApplicationShell>;
}
