/** Group routes, including detail pages addressed by query parameter for the static export. */
export const groupRoutes = {
  list: "/app/groups",
  create: "/app/groups/new",
  view: "/app/groups/view",
} as const;

export function groupViewHref(groupId: string): string {
  return `${groupRoutes.view}?id=${encodeURIComponent(groupId)}`;
}
