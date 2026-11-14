"use client";

import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { cssVariablesResolver, theme } from "@/lib/theme";

// Server-rendered pages stay outside this narrow interactivity/provider boundary.
export function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider
      defaultColorScheme="auto"
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
    >
      {children}
    </MantineProvider>
  );
}
