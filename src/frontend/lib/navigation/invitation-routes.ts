/** The return path carries only the destination, never the invitation bearer. */
export const INVITE_PATH = "/invite";
export const INVITE_RETURN_QUERY = `?next=${encodeURIComponent(INVITE_PATH)}`;

export function invitationHref(token: string, origin: string): string {
  return `${origin}${INVITE_PATH}#${token}`;
}
