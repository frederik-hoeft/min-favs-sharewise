import { useMemo } from "react";
import { useAuthenticatedApis } from "@/app/identity-provider";
import { GroupsApi } from "@/core/groups";

/**
 * The single point that chooses the client for Group Management requests.
 *
 * It uses the session-scoped client that the workspace guard provides for the current sign-in,
 * so requests carry that session's IAM bearer and participate in its renewal and recovery.
 */
export function useGroupsApi(): GroupsApi {
  const { api } = useAuthenticatedApis();
  return useMemo(() => new GroupsApi(api), [api]);
}
