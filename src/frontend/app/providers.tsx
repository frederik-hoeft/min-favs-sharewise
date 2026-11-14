"use client";

import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { cssVariablesResolver, theme } from "@/lib/theme";

// A single client provider keeps the landing page and its static previews server-rendered.
export function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider
      env={process.env.NODE_ENV === "test" ? "test" : "default"}
      defaultColorScheme="auto"
      theme={theme}
      cssVariablesResolver={cssVariablesResolver}
    >
      {children}
    </MantineProvider>
  );
}
