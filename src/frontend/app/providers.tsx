"use client";

import { MantineProvider } from "@mantine/core";
import type { ReactNode } from "react";
import { theme } from "@/lib/theme";

// Keep the client boundary at the provider; routes/layouts may remain server components.
export function Providers({ children }: { children: ReactNode }) {
  return (
    <MantineProvider defaultColorScheme="auto" theme={theme}>
      {children}
    </MantineProvider>
  );
}
