import { Button, createTheme, type CSSVariablesResolver } from "@mantine/core";

/** Candidate B: a calm editorial ledger, with application-wide semantic roles. */
export const theme = createTheme({
  primaryColor: "forest",
  primaryShade: { light: 7, dark: 4 },
  autoContrast: true,
  colors: {
    forest: [
      "#edf6f1", "#d8ebe0", "#b7d6c4", "#91bea5", "#6fa98b",
      "#508e70", "#347359", "#225d48", "#164b3b", "#0d382c",
    ],
    clay: [
      "#fff4e8", "#ffe8ce", "#f9d2a9", "#f0bb86", "#e9a56a",
      "#d68b51", "#b96c39", "#96512d", "#743b24", "#542b1e",
    ],
  },
  fontFamily: '"Avenir Next", "Segoe UI", Arial, sans-serif',
  fontFamilyMonospace: '"SFMono-Regular", Consolas, monospace',
  headings: {
    fontFamily: '"Avenir Next", "Segoe UI", Arial, sans-serif',
    fontWeight: "700",
    textWrap: "balance",
  },
  defaultRadius: "md",
  radius: { xs: "4px", sm: "8px", md: "12px", lg: "18px", xl: "24px" },
  spacing: {
    xs: "8px", sm: "12px", md: "16px", lg: "24px", xl: "32px",
  },
  focusRing: "auto",
  respectReducedMotion: true,
  components: {
    Button: Button.extend({
      defaultProps: { radius: "xl" },
      styles: { root: { fontWeight: 700, letterSpacing: "-0.01em" } },
    }),
  },
});

/** Semantic surface roles; page components must not duplicate these hex values. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-display-font": 'Georgia, "Times New Roman", serif',
    "--sw-content-width": "1200px",
  },
  light: {
    "--sw-page": "#f8f8f3",
    "--sw-paper": "#ffffff",
    "--sw-elevated": "#fcfdf9",
    "--sw-ink": "#1b3029",
    "--sw-brand": "#225d48",
    "--sw-muted": "#60746a",
    "--sw-line": "#dfe6db",
    "--sw-hero": "#e8f0e6",
    "--sw-hero-accent": "#d3e3d6",
    "--sw-highlight": "#e4f1e5",
    "--sw-warm": "#f5e7d4",
    "--sw-warm-ink": "#805135",
    "--sw-negative": "#a45b42",
    "--sw-positive": "#1b6a50",
    "--sw-deep": "#193c32",
    "--sw-deep-ink": "#f8f8ef",
    "--sw-shadow": "0 28px 75px rgba(22, 62, 44, 0.13)",
  },
  dark: {
    "--sw-page": "#101c18",
    "--sw-paper": "#1a2923",
    "--sw-elevated": "#21332b",
    "--sw-ink": "#f1f5ed",
    "--sw-brand": "#6fa98b",
    "--sw-muted": "#b4c4b9",
    "--sw-line": "#34483d",
    "--sw-hero": "#1a3127",
    "--sw-hero-accent": "#254436",
    "--sw-highlight": "#244939",
    "--sw-warm": "#483829",
    "--sw-warm-ink": "#f4c18a",
    "--sw-negative": "#ecab91",
    "--sw-positive": "#a3ddbc",
    "--sw-deep": "#0c1613",
    "--sw-deep-ink": "#f1f5ed",
    "--sw-shadow": "0 28px 75px rgba(0, 0, 0, 0.33)",
  },
});
