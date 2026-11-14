import {
  Badge,
  Button,
  Card,
  createTheme,
  type CSSVariablesResolver,
} from "@mantine/core";

/** Candidate B4: the same semantic roles apply to marketing and application surfaces. */
export const theme = createTheme({
  fontFamily: '"Segoe UI", Inter, system-ui, -apple-system, sans-serif',
  fontFamilyMonospace: 'ui-monospace, "SFMono-Regular", Consolas, monospace',
  headings: {
    fontFamily: '"Segoe UI", Inter, system-ui, -apple-system, sans-serif',
    fontWeight: "700",
  },
  primaryColor: "pine",
  primaryShade: { light: 8, dark: 7 },
  autoContrast: true,
  luminanceThreshold: 0.45,
  defaultRadius: "lg",
  radius: { xs: "4px", sm: "8px", md: "12px", lg: "18px", xl: "28px" },
  spacing: { xs: "0.5rem", sm: "0.75rem", md: "1rem", lg: "1.5rem", xl: "2.25rem" },
  colors: {
    pine: ["#edf6f0", "#d9ede0", "#b8d8c4", "#96c0a8", "#6fa58a", "#50846b", "#366e57", "#285644", "#184334", "#0d2b23"],
    clay: ["#fff5ed", "#ffe8d9", "#f8c8ab", "#e8ad87", "#d28b64", "#b16d45", "#92552f", "#714122", "#53331e", "#382319"],
    citron: ["#f9fce9", "#f1f9ce", "#e7f4ae", "#daf08e", "#cbea65", "#b9d947", "#9ebc2e", "#819921", "#687a1b", "#4e5d13"],
  },
  components: {
    Button: Button.extend({ defaultProps: { radius: "xl", size: "md" }, styles: { root: { fontWeight: 700 } } }),
    Card: Card.extend({ defaultProps: { radius: "lg", padding: "lg", withBorder: true } }),
    Badge: Badge.extend({ defaultProps: { radius: "xl", variant: "light" } }),
  },
});

/** Semantic CSS properties intentionally supplement Mantine's component tokens. */
export const cssVariablesResolver: CSSVariablesResolver = () => ({
  variables: {
    "--sw-display-font": 'Georgia, "Times New Roman", serif',
  },
  light: {
    "--sw-page": "#f7f8f2",
    "--sw-ink": "#16352b",
    "--sw-muted": "#53685d",
    "--sw-surface": "#ffffff",
    "--sw-soft": "#ebf0e8",
    "--sw-line": "#dbe4d9",
    "--sw-highlight": "#ddf297",
    "--sw-highlight-ink": "#16352b",
    "--sw-deep": "#153d30",
    "--sw-deep-ink": "#f5f9eb",
    "--sw-coral": "#e8bf9f",
    "--sw-shadow": "0 24px 70px rgba(20, 47, 34, 0.10)",
    "--sw-credit": "#226b4c",
    "--sw-debit": "#a14d38",
  },
  dark: {
    "--sw-page": "#101d19",
    "--sw-ink": "#eef6ec",
    "--sw-muted": "#b0c2b6",
    "--sw-surface": "#1b3028",
    "--sw-soft": "#243d32",
    "--sw-line": "#375446",
    "--sw-highlight": "#d9ef90",
    "--sw-highlight-ink": "#15352a",
    "--sw-deep": "#0a1611",
    "--sw-deep-ink": "#eef6ec",
    "--sw-coral": "#e8bf9f",
    "--sw-shadow": "0 24px 70px rgba(0, 0, 0, 0.3)",
    "--sw-credit": "#9be8ae",
    "--sw-debit": "#ffbca1",
  },
});
