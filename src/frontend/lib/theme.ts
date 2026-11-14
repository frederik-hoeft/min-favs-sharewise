import {
  Badge,
  Button,
  Card,
  SegmentedControl,
  createTheme,
  defaultVariantColorsResolver,
  type CSSVariablesResolver,
  type VariantColorsResolver,
} from "@mantine/core";

// Preserve AA contrast on the brighter dark-mode brand shade. Mantine's
// standard resolver otherwise assigns white text to this shade.
const variantColorResolver: VariantColorsResolver = (input) => {
  const result = defaultVariantColorsResolver(input);

  if (input.variant === "filled" && (input.color ?? input.theme.primaryColor) === "brand") {
    return { ...result, color: "var(--sw-on-brand)", hoverColor: "var(--sw-on-brand)" };
  }

  return result;
};

// Single source for candidate-specific brand and semantic UI surfaces.
// Values are intentionally neutral; the brand is an accent, not a page background.
export const theme = createTheme({
  primaryColor: "brand",
  variantColorResolver,
  primaryShade: { light: 7, dark: 5 },
  colors: {
    brand: [
      "#effbf8", "#d9f4ed", "#b5e9dc", "#8bdacb", "#66c9b7",
      "#3bb29e", "#168e7d", "#106d61", "#0e554c", "#0b413b",
    ],
  },
  fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif',
  headings: {
    fontFamily: 'Inter, ui-sans-serif, system-ui, -apple-system, "Segoe UI", sans-serif',
    fontWeight: "650",
  },
  radius: { xs: "4px", sm: "6px", md: "9px", lg: "13px", xl: "18px" },
  spacing: { xs: "8px", sm: "12px", md: "16px", lg: "24px", xl: "32px" },
  defaultRadius: "md",
  components: {
    Button: Button.extend({
      defaultProps: { radius: "md", autoContrast: true },
      styles: { root: { fontWeight: 650, letterSpacing: "-0.015em" } },
    }),
    Card: Card.extend({
      defaultProps: { radius: "lg", withBorder: true },
      styles: { root: { background: "var(--sw-surface)", borderColor: "var(--sw-line)" } },
    }),
    Badge: Badge.extend({ defaultProps: { radius: "sm", variant: "light" } }),
    SegmentedControl: SegmentedControl.extend({
      defaultProps: { radius: "md", size: "xs" },
    }),
  },
});

export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-content-width": "1200px",
  },
  light: {
    "--sw-canvas": "#f8f9f7",
    "--sw-surface": "#ffffff",
    "--sw-surface-muted": "#f2f5f3",
    "--sw-surface-accent": "#eaf5f1",
    "--sw-text": "#202d30",
    "--sw-text-muted": "#5d6b6e",
    "--sw-line": "#dce4e1",
    "--sw-line-strong": "#c8d4d0",
    "--sw-positive": "#116f60",
    "--sw-negative": "#9b543c",
    "--sw-neutral": "#657476",
    "--sw-brand-text": "#0d6458",
    "--sw-on-brand": "#ffffff",
  },
  dark: {
    "--sw-canvas": "#111a1c",
    "--sw-surface": "#1b272a",
    "--sw-surface-muted": "#223034",
    "--sw-surface-accent": "#1b3935",
    "--sw-text": "#f0f4f2",
    "--sw-text-muted": "#aabbb9",
    "--sw-line": "#354548",
    "--sw-line-strong": "#4a5e5e",
    "--sw-positive": "#78d8c4",
    "--sw-negative": "#f0ac91",
    "--sw-neutral": "#abb9b7",
    "--sw-brand-text": "#86dfcd",
    "--sw-on-brand": "#092c25",
  },
});
