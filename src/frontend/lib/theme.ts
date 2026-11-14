import { Button, Card, createTheme, type CSSVariablesResolver } from "@mantine/core";

/** Candidate 9: warm architectural minimalism. All colors are local, not remote assets. */
export const theme = createTheme({
  primaryColor: "copper",
  primaryShade: { light: 7, dark: 6 },
  colors: {
    copper: [
      "#fcf1ea", "#f7dfd0", "#edbda5", "#dc9878", "#cc7857",
      "#bd684a", "#aa573a", "#93472f", "#763b2a", "#5b3025",
    ],
    stone: [
      "#fcfaf6", "#f5f2ec", "#ebe7df", "#dbd5ca", "#c8beb0",
      "#aaa092", "#81786e", "#625b54", "#403b36", "#282521",
    ],
  },
  black: "#25221f",
  white: "#fffdfa",
  fontFamily: '"Avenir Next", "Segoe UI", Arial, sans-serif',
  fontFamilyMonospace: '"SFMono-Regular", Consolas, monospace',
  headings: {
    fontFamily: '"Avenir Next", "Segoe UI", Arial, sans-serif',
    fontWeight: "600",
  },
  defaultRadius: "md",
  radius: { xs: "4px", sm: "6px", md: "10px", lg: "16px", xl: "24px" },
  spacing: { xs: "8px", sm: "12px", md: "16px", lg: "24px", xl: "32px" },
  respectReducedMotion: true,
  components: {
    Button: Button.extend({ defaultProps: { radius: "sm" } }),
    Card: Card.extend({ defaultProps: { radius: "lg", withBorder: true } }),
  },
});

/** Semantic surface tokens allow marketing pages and actual workspace UIs to share styles. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-feature-bg": "#282521",
    "--sw-feature-fg": "#fffdfa",
    "--sw-emphasis-fg": "#fff9f1",
    "--sw-content-width": "1200px",
  },
  light: {
    "--sw-canvas": "#f6f3ed",
    "--sw-paper": "#fffdfa",
    "--sw-raised": "#f0ebe3",
    "--sw-ink": "#25221f",
    "--sw-secondary": "#645d54",
    "--sw-faint": "#645d54",
    "--sw-line": "#ded8cf",
    "--sw-accent": "#93472f",
    "--sw-accent-tint": "#f5e5db",
    "--sw-emphasis-bg": "#292723",
    "--sw-positive": "#765642",
    "--sw-negative": "#655e56",
  },
  dark: {
    "--sw-canvas": "#1e1c1a",
    "--sw-paper": "#282522",
    "--sw-raised": "#322e2a",
    "--sw-ink": "#f5f0e9",
    "--sw-secondary": "#c4baae",
    "--sw-faint": "#a89d91",
    "--sw-line": "#49433d",
    "--sw-accent": "#dca084",
    "--sw-accent-tint": "#443126",
    "--sw-emphasis-bg": "#473429",
    "--sw-positive": "#ddb6a0",
    "--sw-negative": "#c4baae",
  },
});
