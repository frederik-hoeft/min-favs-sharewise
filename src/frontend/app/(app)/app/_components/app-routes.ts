/** Navigation labels are also used for breadcrumb titles. Routes remain independent of UI icons. */
export const appRoutes = [
  { href: "/app/dummy1", label: "Dummy 1" },
  { href: "/app/dummy2", label: "Dummy 2" },
] as const;

export type AppBreadcrumb = { href: string; label: string };

/** Build breadcrumbs relative to /app, including any future nested feature routes. */
export function appBreadcrumbs(pathname: string): AppBreadcrumb[] {
  const segments = pathname.split("/").filter(Boolean);
  const crumbs: AppBreadcrumb[] = [{ href: "/app", label: "Workspace" }];
  if (segments[0] !== "app") return crumbs;

  let href = "/app";
  for (const segment of segments.slice(1)) {
    href += `/${segment}`;
    const known = appRoutes.find((route) => route.href === href);
    const label = known?.label ?? segment.replace(/[-_]+/g, " ").replace(/\b\w/g, (char) => char.toUpperCase());
    crumbs.push({ href, label });
  }
  return crumbs;
}
