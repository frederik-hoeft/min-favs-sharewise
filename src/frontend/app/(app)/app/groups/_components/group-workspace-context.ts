"use client";

import { createContext, useContext } from "react";
import type { GroupWorkspace } from "@/core/groups";

export const GroupWorkspaceContext = createContext<GroupWorkspace | null>(null);

/** Read the authorized workspace snapshot from a descendant group page without a second request. */
export function useGroupWorkspace(): GroupWorkspace {
  const workspace = useContext(GroupWorkspaceContext);
  if (!workspace) throw new Error("GroupWorkspaceLayout is missing");
  return workspace;
}
