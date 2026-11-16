import { Button, Card, createTheme, type CSSVariablesResolver, Menu, Paper } from "@mantine/core";

/** Shared Quiet clarity foundation: graphite neutrals and a non-green indigo accent. */
export const theme = createTheme({
  primaryColor: "indigoBrand",
  primaryShade: { light: 6, dark: 4 },
  autoContrast: true,
  luminanceThreshold: 0.23,
  colors: {
    indigoBrand: [
      "#f2f3ff", "#e5e7ff", "#c9cdff", "#a7adff", "#858af7",
      "#666dec", "#5057dc", "#3c43bf", "#2e3494", "#202468",
    ],
  },
  fontFamily: '"Inter", "Segoe UI", Arial, sans-serif',
  headings: {
    fontFamily: '"Inter", "Segoe UI", Arial, sans-serif',
    fontWeight: "650",
    textWrap: "balance",
  },
  defaultRadius: "md",
  radius: { xs: "0.3125rem", sm: "0.5625rem", md: "0.875rem", lg: "1.25rem", xl: "1.75rem" },
  spacing: { xs: "0.5rem", sm: "0.75rem", md: "1rem", lg: "1.5rem", xl: "2rem" },
  components: {
    Button: Button.extend({
      defaultProps: { radius: "xl" },
      // Primary action foreground tracks scheme changes without depending on SSR color inference.
      styles: (_theme, props) =>
        (props.variant === undefined || props.variant === "filled") &&
        (props.color === undefined || props.color === "indigoBrand")
          ? { root: { color: "var(--sw-action-ink)" } }
          : {},
    }),
    Card: Card.extend({
      defaultProps: { radius: "xl", withBorder: true },
      styles: { root: { background: "var(--sw-surface)", borderColor: "var(--sw-border)" } },
    }),
    Paper: Paper.extend({
      defaultProps: { radius: "xl" },
    }),
    Menu: Menu.extend({
      defaultProps: { radius: "md", shadow: "md" },
    }),
  },
});

/** Scheme-specific semantic roles, shared by public and future application views. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--container-size-content": "73.75rem",
    "--sw-content-width": "var(--container-size-content)",
    "--sw-on-inverse": "#ffffff",
    "--sw-summary": "#272a4b",
    "--sw-summary-muted": "#c1c4e7",
    "--sw-summary-border": "#626897",
    "--sw-inverse-muted": "#d4d6ef",
    "--sw-inverse-kicker": "#bfc4ff",
    "--sw-inverse-mark": "#909aff",
    "--sw-accent": "var(--mantine-primary-color-filled)",
  },
  light: {
    "--sw-page": "#f8f8fa",
    "--sw-surface": "#ffffff",
    "--sw-surface-subtle": "#f2f3f7",
    "--sw-surface-accent": "#eeefff",
    "--sw-ink": "#191b2b",
    "--sw-muted": "#60657a",
    "--sw-border": "#e3e5ed",
    "--sw-shadow": "rgba(27, 31, 65, 0.085)",
    "--sw-hero-glow": "#e6e8ff",
    "--sw-inverse": "#1b1e33",
    "--sw-action-ink": "#ffffff",
  },
  dark: {
    "--sw-page": "#11131c",
    "--sw-surface": "#1d2030",
    "--sw-surface-subtle": "#262a3a",
    "--sw-surface-accent": "#292c53",
    "--sw-ink": "#f2f2fb",
    "--sw-muted": "#aeb3c9",
    "--sw-border": "#35394e",
    "--sw-shadow": "rgba(0, 0, 0, 0.26)",
    "--sw-hero-glow": "#22284c",
    "--sw-inverse": "#252945",
    "--sw-action-ink": "#17192b",
  },
});
