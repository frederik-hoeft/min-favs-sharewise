import {
  ActionIcon,
  Badge,
  Button,
  Card,
  createTheme,
  type CSSVariablesResolver,
  Paper,
} from "@mantine/core";

/**
 * Phase B candidate: clear corporate surfaces, calm navy text and blue/teal accents.
 * Semantic --sw-* tokens are shared by marketing and illustrative product interfaces.
 */
export const theme = createTheme({
  colors: {
    brand: [
      "#eaf2ff", "#d5e5ff", "#b3d0ff", "#86b1fb", "#5e8ef2",
      "#416fdf", "#3259c5", "#294a9f", "#233e80", "#1b2f62",
    ],
    ocean: [
      "#e4faf7", "#c6f2eb", "#9ee7dd", "#6bd4c8", "#35bfb2",
      "#129c93", "#087d78", "#066660", "#08524e", "#083e3b",
    ],
    slate: [
      "#f3f7fa", "#eaf0f5", "#dce6ee", "#bbccd9", "#8da6b8",
      "#687f93", "#4a6175", "#334b61", "#21374d", "#12273b",
    ],
  },
  primaryColor: "brand",
  primaryShade: { light: 6, dark: 6 },
  fontFamily: '"Segoe UI", "Helvetica Neue", Arial, sans-serif',
  headings: {
    fontFamily: '"Segoe UI", "Helvetica Neue", Arial, sans-serif',
    fontWeight: "700",
  },
  defaultRadius: "md",
  radius: {
    xs: "0.375rem",
    sm: "0.5rem",
    md: "0.75rem",
    lg: "1rem",
    xl: "1.5rem",
  },
  spacing: {
    xs: "0.5rem",
    sm: "0.75rem",
    md: "1rem",
    lg: "1.5rem",
    xl: "2rem",
  },
  components: {
    Button: Button.extend({
      defaultProps: { radius: "md" },
      styles: { root: { fontWeight: 650, letterSpacing: "-0.01em" } },
    }),
    ActionIcon: ActionIcon.extend({ defaultProps: { radius: "md" } }),
    Badge: Badge.extend({ defaultProps: { radius: "xl" } }),
    Card: Card.extend({ defaultProps: { radius: "lg" } }),
    Paper: Paper.extend({ defaultProps: { radius: "lg" } }),
  },
});

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-content-width": "1200px",
    "--sw-display-shadow": "0 32px 100px rgba(20, 42, 67, 0.14)",
  },
  light: {
    "--sw-canvas": "#f8fafc",
    "--sw-surface": "#ffffff",
    "--sw-surface-raised": "#ffffff",
    "--sw-subtle": "#f0f5f8",
    "--sw-ink": "#162b41",
    "--sw-muted": "#5c7083",
    "--sw-line": "#dae5ed",
    "--sw-accent": "#066f68",
    "--sw-accent-soft": "#e1f5f0",
    "--sw-credit": "#066f68",
    "--sw-debit": "#ac5663",
    "--sw-hero-glow": "rgba(183, 226, 250, 0.56)",
    "--sw-grid": "rgba(32, 86, 135, 0.055)",
    "--sw-panel-shadow": "0 20px 70px rgba(35, 61, 88, 0.07)",
    "--sw-dark-panel": "#142b43",
    "--sw-dark-panel-ink": "#ffffff",
  },
  dark: {
    "--sw-canvas": "#0c1727",
    "--sw-surface": "#132338",
    "--sw-surface-raised": "#1a2d43",
    "--sw-subtle": "#1a2b40",
    "--sw-ink": "#edf5fc",
    "--sw-muted": "#acbdcd",
    "--sw-line": "#304359",
    "--sw-accent": "#6bd4c8",
    "--sw-accent-soft": "#173f47",
    "--sw-credit": "#6bd4c8",
    "--sw-debit": "#ffacb4",
    "--sw-hero-glow": "rgba(33, 92, 137, 0.28)",
    "--sw-grid": "rgba(181, 216, 241, 0.045)",
    "--sw-panel-shadow": "0 20px 70px rgba(0, 0, 0, 0.16)",
    "--sw-dark-panel": "#1b344d",
    "--sw-dark-panel-ink": "#f3f8ff",
  },
});
