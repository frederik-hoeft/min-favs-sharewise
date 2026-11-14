import {
  Badge,
  Button,
  createTheme,
  type CSSVariablesResolver,
} from "@mantine/core";

// Candidate palette: deliberately neutral, with one low-saturation burgundy accent.
export const theme = createTheme({
  primaryColor: "wine",
  primaryShade: { light: 7, dark: 5 },
  colors: {
    wine: [
      "#faf0f1",
      "#f1dcdf",
      "#e5bdc3",
      "#d396a0",
      "#be6e7b",
      "#a54b5b",
      "#883744",
      "#702d39",
      "#57242f",
      "#3f1c24",
    ],
    gray: [
      "#f8f7f4",
      "#f0eeea",
      "#e6e3de",
      "#d5d1cb",
      "#aba69e",
      "#827d76",
      "#655f59",
      "#4c4843",
      "#332f2d",
      "#252322",
    ],
  },
  fontFamily: 'Arial, "Helvetica Neue", Helvetica, sans-serif',
  headings: {
    fontFamily: 'Arial, "Helvetica Neue", Helvetica, sans-serif',
    fontWeight: "600",
  },
  defaultRadius: "xs",
  radius: {
    xs: "0.25rem",
    sm: "0.375rem",
    md: "0.5rem",
    lg: "0.75rem",
    xl: "1rem",
  },
  spacing: {
    xs: "0.5rem",
    sm: "0.75rem",
    md: "1rem",
    lg: "1.5rem",
    xl: "2rem",
  },
  focusRing: "auto",
  components: {
    Button: Button.extend({
      defaultProps: { radius: "xs" },
      styles: { root: { fontWeight: 600, letterSpacing: "-0.015em" } },
    }),
    Badge: Badge.extend({
      defaultProps: { radius: "xs", variant: "light" },
      styles: { root: { fontWeight: 600, letterSpacing: "0.035em" } },
    }),
  },
});

/** Shared semantic surface roles for both the site and later application UI. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-max-content": "1240px",
    "--sw-page-gutter": "clamp(1.25rem, 4.5vw, 5.5rem)",
    "--sw-section-space": "clamp(5rem, 9vw, 10rem)",
  },
  light: {
    "--sw-canvas": "#f7f6f3",
    "--sw-paper": "#fffefa",
    "--sw-ink": "#252322",
    "--sw-muted": "#69645e",
    "--sw-line": "#d9d5ce",
    "--sw-soft": "#efede8",
    "--sw-accent": "#702d39",
    "--sw-accent-quiet": "#f3e8e8",
    "--sw-inverse": "#262224",
    "--sw-inverse-ink": "#faf7f2",
    "--sw-inverse-muted": "#c3b8b6",
    "--sw-inverse-accent": "#d69aa5",
    "--sw-inverse-line": "#5a5051",
  },
  dark: {
    "--sw-canvas": "#191817",
    "--sw-paper": "#22211f",
    "--sw-ink": "#f0ede8",
    "--sw-muted": "#b6afa6",
    "--sw-line": "#48443f",
    "--sw-soft": "#2c2a27",
    "--sw-accent": "#db9ca6",
    "--sw-accent-quiet": "#3a292e",
    "--sw-inverse": "#2c2528",
    "--sw-inverse-ink": "#faf7f2",
    "--sw-inverse-muted": "#cbbfc0",
    "--sw-inverse-accent": "#d69aa5",
    "--sw-inverse-line": "#62565a",
  },
});
