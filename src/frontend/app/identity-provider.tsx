"use client";

import { createContext, useContext, useEffect, useState, useSyncExternalStore } from "react";
import type { Context, Dispatch, ReactElement, ReactNode, SetStateAction } from "react";
import { createIdentityRuntime, type AuthenticatedApis, type AuthState, type IdentityRuntime } from "@/core/identity";

const IdentityContext: Context<IdentityRuntime | null> = createContext<IdentityRuntime | null>(null);
const AuthenticatedApisContext: Context<AuthenticatedApis | null> = createContext<AuthenticatedApis | null>(null);
const INITIAL_STATE: AuthState = { status: "reauthentication-required", reason: "initial" };

/** A single identity runtime for the lifetime of this browser-side React root. */
export function IdentityProvider({ children, runtime: provided }: { children: ReactNode; runtime?: IdentityRuntime }): ReactElement {
  const [runtime]: [IdentityRuntime, Dispatch<SetStateAction<IdentityRuntime>>] = useState(() => provided ?? createIdentityRuntime());
  useEffect(() => {
    runtime.connect();
    // Strict Mode repeats setup/cleanup; do not dispose the retained runtime or its session here.
    return () => runtime.disconnect();
  }, [runtime]);
  return <IdentityContext.Provider value={runtime}>{children}</IdentityContext.Provider>;
}

function useIdentityRuntime(): IdentityRuntime {
  const runtime: IdentityRuntime | null = useContext(IdentityContext);
  if (!runtime) throw new Error("IdentityProvider is missing");
  return runtime;
}

/** Authentication UI and guards use the lifecycle, not the unscoped transport. */
export function useIdentity(): Pick<IdentityRuntime, "auth" | "session"> {
  return useIdentityRuntime();
}

/** Mounted and keyed by the protected route guard, once per stable session. */
export function AuthenticatedApisProvider({ sessionId, children }: { sessionId: string; children: ReactNode }): ReactElement {
  const runtime: IdentityRuntime = useIdentityRuntime();
  const [apis]: [AuthenticatedApis, Dispatch<SetStateAction<AuthenticatedApis>>] = useState(() => runtime.forSession(sessionId));
  return <AuthenticatedApisContext.Provider value={apis}>{children}</AuthenticatedApisContext.Provider>;
}

/** Application components retain a session-scoped capability, even across awaits. */
export function useAuthenticatedApis(): AuthenticatedApis {
  const apis: AuthenticatedApis | null = useContext(AuthenticatedApisContext);
  if (!apis) throw new Error("AuthenticatedApisProvider is missing");
  return apis;
}

/** Subscribe without ever putting the JWT in React state or render output. */
export function useAuthState(): AuthState {
  const { session }: Pick<IdentityRuntime, "auth" | "session"> = useIdentity();
  return useSyncExternalStore(session.subscribe, session.getSnapshot, () => INITIAL_STATE);
}
