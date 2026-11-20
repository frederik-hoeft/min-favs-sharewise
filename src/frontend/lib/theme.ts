import {
  Alert,
  Burger,
  Button,
  Card,
  createTheme,
  defaultVariantColorsResolver,
  Input,
  type CSSVariablesResolver,
  type VariantColorsResolver,
  Menu,
  NavLink,
  Paper,
} from "@mantine/core";

// Color variants are shared by all components (including the public CTA), not
// defined per navigation link or menu instance.
const variantColorResolver: VariantColorsResolver = (input) => {
  const resolved = defaultVariantColorsResolver(input);
  if (input.variant === "subtle" && input.color === "gray") {
    return { ...resolved, hover: "var(--mantine-color-default-hover)", color: "var(--mantine-color-text)" };
  }
  if (input.variant === "filled" && input.color === "indigoBrand") {
    // The accessible primary foreground is shared by Buttons and active NavLinks.
    return { ...resolved, color: "var(--sw-action-ink)" };
  }
  return resolved;
};

/** Shared Quiet clarity foundation: graphite neutrals and a non-green indigo accent. */
export const theme = createTheme({
  primaryColor: "indigoBrand",
  primaryShade: { light: 6, dark: 4 },
  autoContrast: true,
  luminanceThreshold: 0.23,
  variantColorResolver,
  colors: {
    indigoBrand: [
      "#f2f3ff", "#e5e7ff", "#c9cdff", "#a7adff", "#858af7",
      "#666dec", "#5057dc", "#3c43bf", "#2e3494", "#202468",
    ],
    // Mantine uses these built-in palettes for inactive NavLink hover,
    // dropdown controls, default surfaces, and other neutral UI. Customize
    // them once instead of overriding each component's state selectors.
    gray: [
      "#e8eafe", "#e3e5ed", "#d8dce8", "#c5cadd", "#aeb3c9",
      "#8389a1", "#60657a", "#454b65", "#30354b", "#191b2b",
    ],
    dark: [
      "#f2f2fb", "#d4d8e9", "#aeb3c9", "#8d93aa", "#4c526b",
      "#3b425d", "#303654", "#1d2030", "#171a29", "#11131c",
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
    // Input defaults propagate to TextInput, PasswordInput and other Mantine
    // input-based controls; local forms should not independently set radii.
    Input: Input.extend({
      defaultProps: { radius: "sm" },
    }),
    InputWrapper: Input.Wrapper.extend({
      // Keep static guidance above its control and validation feedback below it.
      defaultProps: { inputWrapperOrder: ["label", "description", "input", "error"] },
    }),
    Alert: Alert.extend({
      defaultProps: { variant: "light", radius: "md" },
      // Apply shared semantic danger colors to all error alerts, leaving
      // neutral/informational alerts on their corresponding Mantine palettes.
      styles: (_theme, props) => props.color === "red" ? {
        root: { backgroundColor: "var(--sw-danger-surface)", border: "1px solid var(--sw-danger-border)" },
        icon: { color: "var(--sw-danger-ink)" },
        title: { color: "var(--sw-danger-ink)" },
        message: { color: "var(--sw-ink)" },
      } : {},
    }),
    Button: Button.extend({
      defaultProps: { radius: "xl" },
    }),
    Burger: Burger.extend({
      defaultProps: { color: "var(--mantine-color-text)" },
      // Mantine's root is an unstyled button. Center its glyph in an adequately
      // sized hit target, in both opened and closed states.
      styles: {
        root: {
          alignItems: "center",
          justifyContent: "center",
          minWidth: "2.75rem",
          minHeight: "2.75rem",
        },
      },
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
      styles: {
        dropdown: { background: "var(--sw-surface)", borderColor: "var(--sw-border)" },
        item: { "--menu-item-hover": "var(--mantine-color-default-hover)" },
        divider: { borderColor: "var(--mantine-color-default-border)" },
      },
    }),
    NavLink: NavLink.extend({
      // A selected route is the same primary action as a filled Button.
      // Inactive hover uses our gray-0/dark-6 palette shades automatically.
      defaultProps: { variant: "filled", autoContrast: true },
      styles: { root: { fontWeight: 600, borderRadius: "var(--mantine-radius-md)", whiteSpace: "nowrap" } },
    }),
  },
});

// Mantine declares these on scheme-specific selectors; matching them here
// avoids competing with its more-specific built-in light/dark defaults.
const semanticMantineSchemeColors = {
  "--mantine-color-text": "var(--sw-ink)",
  "--mantine-color-body": "var(--sw-page)",
  "--mantine-color-dimmed": "var(--sw-muted)",
  "--mantine-color-default-hover": "var(--sw-interactive-hover)",
  "--mantine-color-default-color": "var(--sw-ink)",
  "--mantine-color-default-border": "var(--sw-border)",
  "--mantine-color-error": "var(--sw-danger-ink)",
};

/** Scheme-specific semantic roles, shared by public and future application views. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--container-size-content": "73.75rem",
    "--sw-content-width": "var(--container-size-content)",
    "--sw-app-header-height": "4.5rem",
    "--sw-app-navbar-rail-width": "4.35rem",
    "--sw-app-navbar-width": "15.5rem",
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
    ...semanticMantineSchemeColors,
    "--sw-page": "#f8f8fa",
    "--sw-surface": "#ffffff",
    "--sw-surface-subtle": "#f2f3f7",
    "--sw-surface-accent": "#eeefff",
    "--sw-interactive-hover": "#e8eafe",
    "--sw-ink": "#191b2b",
    "--sw-muted": "#60657a",
    "--sw-border": "#e3e5ed",
    "--sw-danger-surface": "#fff1f2",
    "--sw-danger-border": "#eebcc4",
    "--sw-danger-ink": "#9f243b",
    "--sw-shadow": "rgba(27, 31, 65, 0.085)",
    "--sw-hero-glow": "#e6e8ff",
    "--sw-inverse": "#1b1e33",
    "--sw-action-ink": "#ffffff",
  },
  dark: {
    ...semanticMantineSchemeColors,
    "--sw-page": "#11131c",
    "--sw-surface": "#1d2030",
    "--sw-surface-subtle": "#262a3a",
    "--sw-surface-accent": "#292c53",
    "--sw-interactive-hover": "#303654",
    "--sw-ink": "#f2f2fb",
    "--sw-muted": "#aeb3c9",
    "--sw-border": "#35394e",
    "--sw-danger-surface": "#302127",
    "--sw-danger-border": "#754450",
    "--sw-danger-ink": "#ffb4c1",
    "--sw-shadow": "rgba(0, 0, 0, 0.26)",
    "--sw-hero-glow": "#22284c",
    "--sw-inverse": "#252945",
    "--sw-action-ink": "#17192b",
  },
});
