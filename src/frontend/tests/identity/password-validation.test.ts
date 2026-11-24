import { describe, expect, it } from "vitest";
import { hasValidSurrogatePairs, validatePasswordChange } from "@/core/identity/password-validation";

describe("Password-change input policy", () => {
  it("validates current and new passwords independently, including distinct new password", () => {
    expect(validatePasswordChange("", "")).toEqual({
      currentPassword: "Enter your current password.", newPassword: "Enter a new password.",
    });
    expect(validatePasswordChange("short", "short")).toMatchObject({ newPassword: "Use at least 8 characters." });
    expect(validatePasswordChange("same-password", "same-password")).toMatchObject({ newPassword: expect.stringContaining("different") });
    expect(validatePasswordChange("short", "long-enough")).toEqual({});
  });

  it("uses UTF-16 code units and rejects lone surrogate halves without truncating input", () => {
    expect(validatePasswordChange("old", "🙂".repeat(4))).toEqual({});
    expect(validatePasswordChange("old", "🙂".repeat(128))).toEqual({});
    expect(validatePasswordChange("old", "🙂".repeat(129)).newPassword).toMatch(/256/);
    expect(validatePasswordChange("a".repeat(257), "new-password").currentPassword).toMatch(/256/);
    expect(hasValidSurrogatePairs("🙂")).toBe(true);
    expect(hasValidSurrogatePairs("a\ud800b")).toBe(false);
    expect(hasValidSurrogatePairs("\udc00")).toBe(false);
    expect(validatePasswordChange("old", "abc\ud800defgh").newPassword).toMatch(/invalid/);
    expect(validatePasswordChange("\udc00", "new-password").currentPassword).toMatch(/invalid/);
  });
});
