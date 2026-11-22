"use client";

import { createContext, useContext, useEffect, useState, useSyncExternalStore } from "react";
import type { ReactNode } from "react";
import { createIdentityRuntime, type AuthState, type IdentityRuntime } from "@/core/identity";

const IdentityContext = createContext<IdentityRuntime | null>(null);
const INITIAL_STATE: AuthState = { status: "reauthentication-required", reason: "initial" };

/** A single identity runtime for the lifetime of this browser-side React root. */
export function IdentityProvider({ children, runtime: provided }: { children: ReactNode; runtime?: IdentityRuntime }) {
  const [runtime] = useState(() => provided ?? createIdentityRuntime());
  useEffect(() => () => {
    if (!provided) runtime.dispose();
  }, [runtime, provided]);
  return <IdentityContext.Provider value={runtime}>{children}</IdentityContext.Provider>;
}

export function useIdentity(): IdentityRuntime {
  const runtime = useContext(IdentityContext);
  if (!runtime) throw new Error("IdentityProvider is missing");
  return runtime;
}

/** Subscribe without ever putting the JWT in React state or render output. */
export function useAuthState(): AuthState {
  const { session } = useIdentity();
  return useSyncExternalStore(session.subscribe, session.getSnapshot, () => INITIAL_STATE);
}
