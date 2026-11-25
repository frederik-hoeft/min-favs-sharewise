const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** Group IDs are UUIDs; anything else cannot name a group and is treated as not found. */
export function isGroupId(value: string | null | undefined): value is string {
    return typeof value === 'string' && UUID.test(value);
}

/** Client-side shape check only; the backend performs canonical parsing and verifies the secret. */
export function isInvitationToken(value: unknown): value is string {
    return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.[A-Za-z0-9_-]{43}$/.test(value);
}
