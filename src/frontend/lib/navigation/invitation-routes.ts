/** The return path carries only the destination, never the invitation bearer. */
export const INVITE_PATH = "/invite";
export const INVITE_RETURN_QUERY = `?next=${encodeURIComponent(INVITE_PATH)}`;

const INVITATION_FRAGMENT_PREFIX = "#token=";

/** Undefined denotes unrelated navigation; an empty credential is an invalid invitation. */
export function invitationTokenFromFragment(hash: string): string | undefined {
  return hash.startsWith(INVITATION_FRAGMENT_PREFIX) ? hash.slice(INVITATION_FRAGMENT_PREFIX.length) : undefined;
}

export function invitationHref(token: string, origin: string): string {
  return `${origin}${INVITE_PATH}${INVITATION_FRAGMENT_PREFIX}${token}`;
}
