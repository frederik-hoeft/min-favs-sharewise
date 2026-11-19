import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { cssVariablesResolver, theme } from "@/lib/theme";

/**
 * Mantine disables detached-target detection and delayed portal transitions in
 * its documented test environment. jsdom has no real element geometry, so
 * production popup behavior otherwise makes menu tests timing-dependent.
 */
export function TestProviders({ children }: { children: ReactNode }) {
  return (
    <MantineProvider
      env="test"
      defaultColorScheme="auto"
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
    >
      {children}
    </MantineProvider>
  );
}
