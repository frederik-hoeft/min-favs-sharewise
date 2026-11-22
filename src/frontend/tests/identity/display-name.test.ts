import { describe, expect, it } from "vitest";
import { validateDisplayName } from "@/core/identity/display-name";

describe("Display name policy hints", () => {
  it("accepts exactly 80 Unicode scalars, non-ASCII and markup-like text without normalization", () => {
    for (const value of ["x".repeat(80), "🦊".repeat(80), "  <b>Alice</b>  ", "e\u0301", "\u200b"]) {
      expect(validateDisplayName(value)).toBeUndefined();
    }
  });

  it.each(["", " ", "\u00a0", "\tA", "A\u0085", "A\u2029", "\udc00", "\ud800", "A".repeat(81), "🦊".repeat(81)])(
    "rejects invalid scalar/control/length case %j", (value) => {
      expect(validateDisplayName(value)).toBeDefined();
    },
  );
});
