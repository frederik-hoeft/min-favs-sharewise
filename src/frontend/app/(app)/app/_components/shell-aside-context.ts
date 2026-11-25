"use client";

import { createContext, useContext } from "react";

export interface ShellAsideControls {
  dismiss: () => void;
  /** Return focus to the origin, or to the disclosure/main region if it is no longer mounted. */
  restoreFocus: (origin: HTMLElement | null) => void;
}

export const ShellAsideControlsContext = createContext<ShellAsideControls | null>(null);

/** Optional regions are composed declaratively; their content can request shell-owned UI actions. */
export function useShellAsideControls(): ShellAsideControls {
  const controls = useContext(ShellAsideControlsContext);
  if (!controls) throw new Error("ApplicationShell aside is missing");
  return controls;
}
