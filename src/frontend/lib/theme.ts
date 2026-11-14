import {
  ActionIcon,
  Badge,
  Button,
  createTheme,
  Menu,
  Paper,
  type CSSVariablesResolver,
} from "@mantine/core";

/** Candidate B: Precision / Shared. All cross-application color decisions live here. */
export const theme = createTheme({
  primaryColor: "cobalt",
  primaryShade: { light: 6, dark: 4 },
  colors: {
    cobalt: [
      "#edf0ff", "#dce3ff", "#bccaff", "#93aaff", "#718bff",
      "#5472ff", "#4162f5", "#3150d8", "#263fa9", "#203685",
    ],
    mint: [
      "#e6fcf4", "#c6f8e5", "#92edcf", "#5fdfb6", "#31cfa0",
      "#15b889", "#119970", "#107858", "#115f49", "#104d3d",
    ],
  },
  fontFamily: '"Segoe UI Variable", "Segoe UI", Inter, ui-sans-serif, system-ui, sans-serif',
  fontFamilyMonospace: '"Cascadia Code", "SFMono-Regular", Consolas, monospace',
  headings: {
    fontFamily: '"Segoe UI Variable", "Segoe UI", Inter, ui-sans-serif, system-ui, sans-serif',
    fontWeight: "750",
  },
  defaultRadius: "md",
  radius: { xs: "5px", sm: "9px", md: "13px", lg: "20px", xl: "28px" },
  spacing: { xs: "0.5rem", sm: "0.75rem", md: "1rem", lg: "1.5rem", xl: "2rem" },
  components: {
    Button: Button.extend({
      defaultProps: { radius: "xl", size: "md" },
      styles: { root: { fontWeight: 700, letterSpacing: "-0.01em" } },
    }),
    ActionIcon: ActionIcon.extend({ defaultProps: { radius: "xl", variant: "subtle" } }),
    Badge: Badge.extend({ defaultProps: { radius: "xl", variant: "light" } }),
    Paper: Paper.extend({ defaultProps: { radius: "lg" } }),
    Menu: Menu.extend({ defaultProps: { shadow: "lg" } }),
  },
});

/** Semantic roles, not a second component theme. Exposed to SCSS Modules. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-content-width": "1240px",
    "--sw-section-space": "clamp(5rem, 9vw, 9rem)",
    "--sw-positive": "#087957",
    "--sw-negative": "#b64c3a",
  },
  light: {
    "--sw-page": "#f7f9fe",
    "--sw-ink": "#121b35",
    "--sw-muted": "#606d86",
    "--sw-faint": "#64728c",
    "--sw-surface": "#ffffff",
    "--sw-surface-2": "#f0f4fc",
    "--sw-line": "#e2e8f2",
    "--sw-accent": "#4162f5",
    "--sw-accent-subtle": "#e9eeff",
    "--sw-hero-glow": "rgba(80, 109, 255, .14)",
    "--sw-card-shadow": "0 18px 55px rgba(26, 42, 96, .055)",
  },
  dark: {
    "--sw-page": "#0c1222",
    "--sw-ink": "#f2f5ff",
    "--sw-muted": "#a6b3cf",
    "--sw-faint": "#8090af",
    "--sw-surface": "#151e32",
    "--sw-surface-2": "#1c2941",
    "--sw-line": "#2a3854",
    "--sw-accent": "#8da0ff",
    "--sw-accent-subtle": "#25345d",
    "--sw-hero-glow": "rgba(74, 103, 255, .21)",
    "--sw-card-shadow": "0 24px 65px rgba(0, 0, 0, .16)",
    "--sw-positive": "#65e5ba",
    "--sw-negative": "#ffa18f",
  },
});
