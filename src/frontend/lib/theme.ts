import { Button, createTheme, type CSSVariablesResolver, Menu } from "@mantine/core";

/** Candidate B: graphite neutrals with an explicitly non-green indigo accent. */
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
  radius: { xs: "5px", sm: "9px", md: "14px", lg: "20px", xl: "28px" },
  spacing: { xs: "8px", sm: "12px", md: "16px", lg: "24px", xl: "32px" },
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
    Menu: Menu.extend({
      defaultProps: { radius: "md", shadow: "md" },
    }),
  },
});

/** Scheme-specific semantic roles, shared by public and future application views. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-content-width": "1180px",
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
