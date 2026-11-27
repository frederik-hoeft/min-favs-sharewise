// @vitest-environment node

import { describe, expect, it } from "vitest";
import {
  GroupsApi,
  classifyGroupProblem,
  codePointCount,
  isGroupId,
  normalizedDescription,
  validateGroupMetadata,
} from "@/core/groups";
import { ApiError } from "@/lib/api";
import { GROUP_ID, details, json, problem, scriptedClient, summary, workspace } from "./support";

describe("GroupsApi", () => {
  it("maps group read and metadata operations to the Group Management endpoints", async () => {
    const fake = scriptedClient();
    const path = `/api/groups/v1/groups/${GROUP_ID}`;
    fake.respond("GET", "/api/groups/v1/groups", json(200, { items: [summary()] }));
    fake.respond("POST", "/api/groups/v1/groups", json(201, details()));
    fake.respond("GET", path, json(200, details()));
    fake.respond("GET", `${path}/workspace`, json(200, workspace()));
    fake.respond("PUT", path, json(200, details({ version: 1 })));
    const api = new GroupsApi(fake.client);

    await expect(api.list()).resolves.toEqual({ items: [summary()] });
    await expect(api.create({ name: "Flat share", description: null })).resolves.toEqual(details());
    await expect(api.get(GROUP_ID)).resolves.toEqual(details());
    await expect(api.getWorkspace(GROUP_ID)).resolves.toEqual(workspace());
    await expect(api.replaceMetadata(GROUP_ID, { name: "Flat", description: null, expectedVersion: 0 }))
      .resolves.toMatchObject({ version: 1 });

    expect(fake.requests).toEqual([
      { method: "GET", path: "/api/groups/v1/groups", body: undefined },
      { method: "POST", path: "/api/groups/v1/groups", body: { name: "Flat share", description: null } },
      { method: "GET", path, body: undefined },
      { method: "GET", path: `${path}/workspace`, body: undefined },
      { method: "PUT", path, body: { name: "Flat", description: null, expectedVersion: 0 } },
    ]);
  });

  it("maps membership operations to membership IDs and requires a 204 acknowledgment", async () => {
    const fake = scriptedClient();
    const membershipId = workspace().members[1].id;
    const path = `/api/groups/v1/groups/${GROUP_ID}/members/${membershipId}`;
    fake.respond("PUT", `${path}/role`, new Response(null, { status: 204 }));
    fake.respond("DELETE", path, new Response(null, { status: 204 }), json(200, {}));
    const api = new GroupsApi(fake.client);
    await api.setMemberRole(GROUP_ID, membershipId, "admin");
    await api.removeMember(GROUP_ID, membershipId);
    await expect(api.removeMember(GROUP_ID, membershipId)).rejects.toBeInstanceOf(ApiError);
    await expect(api.removeMember(GROUP_ID, "../another")).rejects.toMatchObject({ code: "invalid_api_path" });
    expect(fake.requests).toEqual([
      { method: "PUT", path: `${path}/role`, body: { role: "admin" } },
      { method: "DELETE", path, body: undefined },
      { method: "DELETE", path, body: undefined },
    ]);
  });

  it.each([
    [404, "group_member_not_found", "member_not_found"],
    [409, "group_last_member", "last_member"],
    [409, "group_last_administrator", "last_administrator"],
    [409, "group_self_role_change", "self_role_change"],
  ])("classifies membership problem %s %s", (status, code, kind) => {
    expect(classifyGroupProblem(new ApiError({ status, code, title: "", detail: "" })).kind).toBe(kind);
  });

  it("never lets a group ID change the request route", async () => {
    const fake = scriptedClient();
    // The ID is encoded as one segment; the client rejects such noncanonical paths before dispatch.
    await expect(new GroupsApi(fake.client).get("a/../b")).rejects.toMatchObject({ code: "invalid_api_path" });
    expect(fake.requests).toEqual([]);
  });

  it("surfaces Group problem codes as typed problems", async () => {
    const fake = scriptedClient();
    fake.respond("GET", "/api/groups/v1/groups",
      problem(401, "unauthenticated"),
      problem(403, "group_user_unknown"),
      problem(404, "group_not_found"),
      problem(403, "group_admin_required"),
      problem(409, "group_version_conflict"),
      problem(400, "validation_failed", { fieldErrors: [{ field: "name", code: "required", message: "Required" }] }),
      problem(500, "internal_error"),
      new TypeError("offline"));
    const api = new GroupsApi(fake.client);
    const kinds: string[] = [];
    for (let attempt = 0; attempt < 8; attempt += 1) {
      kinds.push(await api.list().then(() => "ok", (error: unknown) => classifyGroupProblem(error).kind));
    }
    expect(kinds).toEqual([
      "unauthenticated", "user_unknown", "not_found", "admin_required",
      "version_conflict", "invalid", "unavailable", "unavailable",
    ]);
  });

  it("does not treat other 403 or 409 codes as Group-specific outcomes", () => {
    expect(classifyGroupProblem(new ApiError({ status: 403, code: "forbidden", title: "", detail: "" })).kind).toBe("unavailable");
    expect(classifyGroupProblem(new ApiError({ status: 409, code: "other", title: "", detail: "" })).kind).toBe("unavailable");
    expect(classifyGroupProblem(new Error("bug")).kind).toBe("unavailable");
  });
});

describe("group metadata validation", () => {
  it("mirrors the server's trimming, length and required rules", () => {
    expect(validateGroupMetadata({ name: "  Trip  ", description: "" })).toEqual({});
    expect(validateGroupMetadata({ name: " \t\n ", description: "" })).toEqual({ name: "required" });
    expect(validateGroupMetadata({ name: "a".repeat(64), description: "b".repeat(512) })).toEqual({});
    expect(validateGroupMetadata({ name: "a".repeat(65), description: "b".repeat(513) }))
      .toEqual({ name: "too_long", description: "too_long" });
  });

  it("counts Unicode code points rather than UTF-16 units", () => {
    const supplementaryLetter = "\u{10400}";
    expect(supplementaryLetter.length).toBe(2);
    expect(codePointCount(supplementaryLetter.repeat(64))).toBe(64);
    expect(validateGroupMetadata({ name: supplementaryLetter.repeat(64), description: "" })).toEqual({});
    expect(validateGroupMetadata({ name: supplementaryLetter.repeat(65), description: "" })).toEqual({ name: "too_long" });
  });

  it("allows only line breaks as control characters in descriptions", () => {
    expect(validateGroupMetadata({ name: "Trip", description: "Day 1\nDay 2\r\nDay 3" })).toEqual({});
    expect(validateGroupMetadata({ name: "Trip", description: "Day 1\rDay 2" })).toEqual({ description: "control_character" });
    expect(validateGroupMetadata({ name: "Trip", description: "a\tb" })).toEqual({ description: "control_character" });
    expect(validateGroupMetadata({ name: "Tr\u0085ip", description: "" })).toEqual({ name: "control_character" });
    expect(validateGroupMetadata({ name: "Line\nbreak", description: "" })).toEqual({ name: "control_character" });
  });

  it("trims like Java's String.strip, keeping no-break spaces", () => {
    expect(normalizedDescription("   note  ")).toBe("note");
    expect(normalizedDescription(" note ")).toBe(" note ");
    expect(normalizedDescription(" \n ")).toBeNull();
    expect(validateGroupMetadata({ name: " ", description: "" })).toEqual({});
  });

  it("accepts only UUID group IDs", () => {
    expect(isGroupId(GROUP_ID)).toBe(true);
    expect(isGroupId("not-a-uuid")).toBe(false);
    expect(isGroupId(null)).toBe(false);
  });
});

describe("Invitation API contracts", () => {
  it("uses a body-only redemption token and exact mutation acknowledgment statuses", async () => {
    const fake = scriptedClient();
    const path = `/api/groups/v1/groups/${GROUP_ID}/invitations`;
    const id = "0193a5c4-7e1f-7c2a-9b3d-000000000111";
    const token = `${id}.${"A".repeat(43)}`;
    const invitation = { id, createdAt: "2026-11-25T12:00:00Z", expiresAt: null, maxUses: 2, useCount: 0 };
    fake.respond("GET", path, json(200, { items: [invitation] }));
    fake.respond("POST", path, json(201, { invitation, token }), json(200, { invitation, token }));
    fake.respond("DELETE", `${path}/${id}`, new Response(null, { status: 204 }));
    fake.respond("POST", "/api/groups/v1/invitations/redeem", json(200, { groupId: GROUP_ID, outcome: "already_member" }));
    const api = new GroupsApi(fake.client);
    expect(await api.listInvitations(GROUP_ID)).toEqual({ items: [invitation] });
    expect(await api.createInvitation(GROUP_ID, { expiresAt: null, maxUses: 2 })).toEqual({ invitation, token });
    await expect(api.createInvitation(GROUP_ID, { expiresAt: null, maxUses: 2 })).rejects.toMatchObject({ code: "unexpected_response" });
    await api.revokeInvitation(GROUP_ID, id);
    expect(await api.redeemInvitation(token)).toEqual({ groupId: GROUP_ID, outcome: "already_member" });
    expect(fake.requests.at(-1)).toEqual({ method: "POST", path: "/api/groups/v1/invitations/redeem", body: { token } });
    expect(fake.requests.every(request => !request.path.includes(token))).toBe(true);
  });

  it.each([["group_invitation_unavailable", "invitation_unavailable"], ["group_invitation_not_found", "invitation_not_found"]])("classifies %s separately from missing groups", (code, kind) => {
    expect(classifyGroupProblem(new ApiError({ status: 404, code, title: "", detail: "" })).kind).toBe(kind);
  });
});
