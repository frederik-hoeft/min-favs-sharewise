"use client";

import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { cssVariablesResolver, theme } from "@/lib/theme";
import { InvitationContinuationProvider } from "./invitation-continuation";
import { IdentityProvider } from "./identity-provider";

// Keep the client boundary at the provider; routes/layouts may remain server components.
export function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider defaultColorScheme="auto" theme={theme} cssVariablesResolver={cssVariablesResolver}>
      <IdentityProvider><InvitationContinuationProvider>{children}</InvitationContinuationProvider></IdentityProvider>
    </MantineProvider>
  );
}
