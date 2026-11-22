import { useMemo } from "react";
import { useIdentity } from "@/app/identity-provider";
import { GroupsApi } from "@/core/groups";

/**
 * The single point that chooses the client for Group Management requests.
 *
 * It uses the identity runtime's client, which attaches the tab's IAM bearer when one exists.
 * Without an active sign-in the backend answers `401` and pages show their sign-in-required state.
 */
export function useGroupsApi(): GroupsApi {
  const { api } = useIdentity();
  return useMemo(() => new GroupsApi(api), [api]);
}
