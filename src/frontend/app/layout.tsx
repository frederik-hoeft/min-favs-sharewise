import { ColorSchemeScript, mantineHtmlProps } from "@mantine/core";
import type { Metadata } from "next";
import { Providers } from "./providers";

import "@mantine/core/styles.css";
import "./globals.scss";

export const metadata: Metadata = {
  title: "ShareWise | More living. Less keeping score.",
  description: "Keep the good memories, and a clear record of shared expenses, splits, external settlements, and group balances.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" {...mantineHtmlProps}>
      <head>
        <ColorSchemeScript defaultColorScheme="auto" />
      </head>
      <body><Providers>{children}</Providers></body>
    </html>
  );
}
