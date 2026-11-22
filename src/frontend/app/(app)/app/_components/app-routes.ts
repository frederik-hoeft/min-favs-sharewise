/** Navigation labels are also used for breadcrumb titles. Routes remain independent of UI icons. */
export const appRoutes = [
  { href: "/app/groups", label: "Groups" },
  { href: "/app/dummy1", label: "Dummy 1" },
  { href: "/app/dummy2", label: "Dummy 2" },
  { href: "/app/dummy3", label: "Dummy 3" },
] as const;

/** Static, navigable ancestor routes for the long-path breadcrumb demo. */
export const breadcrumbDemoRoutes = {
  group: "/app/dummy3/international-collaboration-project",
  history: "/app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history",
  overview: "/app/dummy3/international-collaboration-project/shared-expenses-and-settlement-revision-history/overview",
} as const;

/** Group routes, including detail pages addressed by query parameter for the static export. */
export const groupRoutes = {
  list: "/app/groups",
  create: "/app/groups/new",
  view: "/app/groups/view",
} as const;

export function groupViewHref(groupId: string): string {
  return `${groupRoutes.view}?id=${encodeURIComponent(groupId)}`;
}

/** Labels for nested routes that have no navigation entry of their own. */
const nestedRouteLabels: ReadonlyArray<{ href: string; label: string }> = [
  { href: groupRoutes.create, label: "New group" },
  { href: groupRoutes.view, label: "Group" },
];

export type AppBreadcrumb = { href: string; label: string };

/** Build breadcrumbs relative to /app, including any future nested feature routes. */
export function appBreadcrumbs(pathname: string): AppBreadcrumb[] {
  const segments = pathname.split("/").filter(Boolean);
  const crumbs: AppBreadcrumb[] = [{ href: "/app", label: "Workspace" }];
  if (segments[0] !== "app") return crumbs;

  let href = "/app";
  for (const segment of segments.slice(1)) {
    href += `/${segment}`;
    const known = [...appRoutes, ...nestedRouteLabels].find((route) => route.href === href);
    const label = known?.label ?? segment.replace(/[-_]+/g, " ").replace(/\b\w/g, (char) => char.toUpperCase());
    crumbs.push({ href, label });
  }
  return crumbs;
}
