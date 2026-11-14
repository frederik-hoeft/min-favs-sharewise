import { Button, createTheme, defaultVariantColorsResolver, NativeSelect, type CSSVariablesResolver, type VariantColorsResolver } from "@mantine/core";

/** Keep the normal Mantine variants, but give the primary filled control a
 * scheme-dependent accessible foreground/background pair in both modes. */
const variantColorResolver: VariantColorsResolver = (input) => {
  const defaults = defaultVariantColorsResolver(input);
  if ((input.color ?? "pine") === "pine" && input.variant === "filled") {
    return {
      ...defaults,
      background: "var(--sw-button-bg)",
      hover: "var(--sw-button-hover)",
      color: "var(--sw-button-ink)",
      hoverColor: "var(--sw-button-ink)",
    };
  }
  return defaults;
};

/** Candidate palette: pine ink, parchment surfaces, and a vivid chartreuse signal. */
export const theme = createTheme({
  primaryColor: "pine",
  variantColorResolver,
  primaryShade: { light: 7, dark: 3 },
  colors: {
    pine: ["#e7efec", "#cdded7", "#a6c3b6", "#7da895", "#568b75", "#377159", "#285d49", "#1b473a", "#16392f", "#102a24"],
    lime: ["#f8fce9", "#f1fad5", "#e8f7b3", "#dff393", "#d7ed72", "#cde55b", "#bed447", "#a1b633", "#839628", "#65751e"],
  },
  fontFamily: '"Avenir Next", Avenir, "Segoe UI", Arial, sans-serif',
  headings: {
    fontFamily: '"Avenir Next", Avenir, "Segoe UI", Arial, sans-serif',
    fontWeight: "700",
  },
  defaultRadius: "md",
  radius: { xs: "6px", sm: "10px", md: "16px", lg: "24px", xl: "999px" },
  spacing: { xs: "8px", sm: "12px", md: "18px", lg: "26px", xl: "38px" },
  respectReducedMotion: true,
  components: {
    Button: Button.extend({
      defaultProps: { color: "pine", radius: "xl", size: "md" },
      styles: { root: { fontWeight: 700, letterSpacing: "-0.01em" } },
    }),
    NativeSelect: NativeSelect.extend({ defaultProps: { radius: "xl", size: "sm" } }),
  },
});

// These are semantic roles consumed by app-wide and feature-local SCSS Modules.
// They follow Mantine's resolved light/dark state, including system preference.
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-font-display": 'Georgia, "Iowan Old Style", "Times New Roman", serif',
    "--sw-content-width": "1220px",
  },
  light: {
    "--sw-canvas": "#faf8f0",
    "--sw-canvas-subtle": "#f0f1e8",
    "--sw-surface": "#ffffff",
    "--sw-surface-tint": "#eff4eb",
    "--sw-ink": "#193c34",
    "--sw-ink-muted": "#62756e",
    "--sw-stroke": "#d9e1d7",
    "--sw-accent": "#d7ed72",
    "--sw-accent-ink": "#193c34",
    "--sw-headline-accent": "#7a9843",
    "--sw-button-bg": "#1b473a",
    "--sw-button-hover": "#285d49",
    "--sw-button-ink": "#ffffff",
    "--sw-preview-bg": "#f9fbf6",
    "--sw-preview-stroke": "#e2e8dc",
    "--sw-shadow": "0 24px 75px rgba(22, 57, 47, 0.12)",
  },
  dark: {
    "--sw-canvas": "#111c19",
    "--sw-canvas-subtle": "#1a2924",
    "--sw-surface": "#1e2d27",
    "--sw-surface-tint": "#273d33",
    "--sw-ink": "#eef4e9",
    "--sw-ink-muted": "#adc3b7",
    "--sw-stroke": "#385146",
    "--sw-accent": "#d7ed72",
    "--sw-accent-ink": "#193c34",
    "--sw-headline-accent": "#d7ed72",
    "--sw-button-bg": "#d7ed72",
    "--sw-button-hover": "#bed447",
    "--sw-button-ink": "#193c34",
    "--sw-preview-bg": "#22352c",
    "--sw-preview-stroke": "#395347",
    "--sw-shadow": "0 24px 75px rgba(0, 0, 0, 0.28)",
  },
});
