import { ColorSchemeScript, mantineHtmlProps } from "@mantine/core";
import type { Metadata } from "next";
import { Providers } from "./providers";

import "@mantine/core/styles.css";
import "./globals.scss";

export const metadata: Metadata = {
  title: "ShareWise | Keep the plans. Lose the math.",
  description: "Share group expenses, see balances, record external settlements and revisit financial history with clarity.",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="en" {...mantineHtmlProps}>
      <head>
        <ColorSchemeScript defaultColorScheme="auto" />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
