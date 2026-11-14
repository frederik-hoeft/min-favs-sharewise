"use client";

import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { cssVariablesResolver, theme } from "@/lib/theme";

// The only root client boundary: pages and their static product previews remain server components.
export function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider
      defaultColorScheme="auto"
      cssVariablesResolver={cssVariablesResolver}
      theme={theme}
    >
      {children}
    </MantineProvider>
  );
}
