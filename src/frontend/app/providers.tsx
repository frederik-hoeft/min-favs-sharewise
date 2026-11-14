"use client";

import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { cssVariablesResolver, theme } from "@/lib/theme";

// The provider owns the only shared client boundary; page compositions stay server-rendered.
export function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider
      defaultColorScheme="auto"
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
      env={process.env.NODE_ENV === "test" ? "test" : "default"}
    >
      {children}
    </MantineProvider>
  );
}
