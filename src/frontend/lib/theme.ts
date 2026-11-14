import { ActionIcon, Button, Card, createTheme, Menu, type CSSVariablesResolver } from "@mantine/core";

/** Candidate 3: an editorial, warm-ledger visual identity. */
export const theme = createTheme({
  primaryColor: "pine",
  primaryShade: { light: 8, dark: 4 },
  colors: {
    pine: ["#edf4ef", "#dbe9df", "#b9d0c2", "#96b5a1", "#749b84", "#57816b", "#3e6a54", "#2c5543", "#183c31", "#102921"],
    citron: ["#f7fae8", "#eef5cc", "#e3eeae", "#d3e583", "#c3dc60", "#b0cb46", "#91a832", "#738629", "#576621", "#414e1b"],
  },
  fontFamily: '"Avenir Next", "Segoe UI", Arial, sans-serif',
  fontFamilyMonospace: '"SFMono-Regular", Consolas, monospace',
  headings: {
    fontFamily: '"Iowan Old Style", "Baskerville", Georgia, serif',
    fontWeight: "500",
    sizes: {
      h1: { fontSize: "clamp(3.25rem, 7vw, 6.9rem)", lineHeight: "1.04" },
      h2: { fontSize: "clamp(2.4rem, 5vw, 4.3rem)", lineHeight: "1.08" },
    },
  },
  radius: { xs: "4px", sm: "8px", md: "12px", lg: "18px", xl: "28px" },
  spacing: { xs: "0.5rem", sm: "0.75rem", md: "1rem", lg: "1.5rem", xl: "2rem" },
  defaultRadius: "md",
  autoContrast: true,
  components: {
    Button: Button.extend({ defaultProps: { radius: "xl", size: "md" } }),
    ActionIcon: ActionIcon.extend({ defaultProps: { radius: "xl", variant: "subtle" } }),
    Card: Card.extend({ defaultProps: { radius: "lg", padding: "lg", withBorder: true } }),
    Menu: Menu.extend({ defaultProps: { radius: "md", shadow: "md" } }),
  },
});

/** Semantic roles are used by both application previews and marketing compositions. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-content-width": "1180px",
    "--sw-editorial-font": '"Iowan Old Style", "Baskerville", Georgia, serif',
  },
  light: {
    "--sw-page": "#f7f7f0",
    "--sw-ink": "#173a30",
    "--sw-muted": "#65766d",
    "--sw-line": "#dce3d8",
    "--sw-surface": "#ffffff",
    "--sw-surface-soft": "#eff2e9",
    "--sw-accent": "#c6de78",
    "--sw-accent-ink": "#1c3527",
    "--sw-peach": "#f3d6c4",
    "--sw-peach-ink": "#593d31",
    "--sw-positive": "#2f7556",
    "--sw-display-accent": "#3e6a54",
    "--sw-negative": "#a15c48",
    "--sw-panel": "#173b32",
    "--sw-panel-ink": "#f6f7eb",
  },
  dark: {
    "--sw-page": "#101c19",
    "--sw-ink": "#e9f2e8",
    "--sw-muted": "#a5b8ae",
    "--sw-line": "#34483d",
    "--sw-surface": "#192b25",
    "--sw-surface-soft": "#21352d",
    "--sw-accent": "#c6de78",
    "--sw-accent-ink": "#1c3527",
    "--sw-peach": "#604235",
    "--sw-peach-ink": "#ffe2ce",
    "--sw-positive": "#a6dfa9",
    "--sw-display-accent": "#c6de78",
    "--sw-negative": "#eab39c",
    "--sw-panel": "#263e33",
    "--sw-panel-ink": "#f5f7ea",
  },
});
