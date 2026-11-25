import { Suspense, type ReactNode } from "react";
import { ApplicationShell } from "../../_components/ApplicationShell";
import { GroupWorkspaceLayout } from "../_components/GroupWorkspaceLayout";
import { GroupLoadingState } from "../_components/GroupProblemState";

export default function GroupViewLayout({ children }: { children: ReactNode }) {
  return <Suspense fallback={<ApplicationShell><GroupLoadingState label="Loading group…" /></ApplicationShell>}><GroupWorkspaceLayout>{children}</GroupWorkspaceLayout></Suspense>;
}
