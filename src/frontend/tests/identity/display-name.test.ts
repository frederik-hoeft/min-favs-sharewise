import { describe, expect, it } from "vitest";
import { validateDisplayName, withinDisplayNameLimit } from "@/core/identity/display-name";

describe("Display name policy hints", () => {
  it("provides basic length and text hints without implementing the authoritative character allowlist", () => {
    for (const value of ["x".repeat(80), "𐐀".repeat(80), "  <b>Alice</b>  ", "e\u0301"]) {
      expect(validateDisplayName(value)).toBeUndefined();
    }
  });

  it("uses Unicode scalar counts for client-side input limits", () => {
    expect(withinDisplayNameLimit("A".repeat(80))).toBe(true);
    expect(withinDisplayNameLimit("𐐀".repeat(80))).toBe(true);
    expect(withinDisplayNameLimit("𐐀".repeat(81))).toBe(false);
    expect(withinDisplayNameLimit("A".repeat(81))).toBe(false);
    expect(validateDisplayName("𐐀".repeat(81))).toBe("Enter at most 80 characters.");
  });

  it.each(["", " ", "\u00a0", "\u200b", "\u0301", "\tA", "A\u0085", "A\u2029", "\udc00", "\ud800", "A".repeat(81), "𐐀".repeat(81)])(
    "rejects invalid scalar/control/length case %j", (value) => {
      expect(validateDisplayName(value)).toBeDefined();
    },
  );
});
