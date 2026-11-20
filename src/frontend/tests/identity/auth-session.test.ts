import { afterEach, describe, expect, it, vi } from "vitest";
import { AuthSession } from "@/core/identity";
import { authenticationResponse } from "./support";

describe("AuthSession (memory-only bearer)", () => {
  afterEach(() => vi.useRealTimers());

  it("starts unauthenticated, exposes only status and expiry, and never writes browser storage", () => {
    const set = vi.spyOn(Storage.prototype, "setItem");
    const session = new AuthSession();
    const listener = vi.fn();
    const unsubscribe = session.subscribe(listener);
    try {
      expect(session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "initial" });
      const response = authenticationResponse();
      session.accept(response);
      expect(session.getAccessToken()).toBe(response.accessToken);
      expect(session.getSnapshot()).toEqual({ status: "authenticated", expiresAt: response.expiresAt });
      expect(JSON.stringify(session.getSnapshot())).not.toContain(response.accessToken);
      expect(listener).toHaveBeenCalledTimes(1);
      expect(set).not.toHaveBeenCalled();
      unsubscribe();
      session.invalidate("unauthorized");
      expect(listener).toHaveBeenCalledTimes(1);
      expect(session.getAccessToken()).toBeUndefined();
    } finally {
      session.dispose();
      set.mockRestore();
    }
  });

  it("invalidates expired credentials both on timer expiry and during request attachment", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-20T12:00:00.000Z"));
    const session = new AuthSession();
    try {
      session.accept(authenticationResponse("first", new Date(Date.now() + 1000).toISOString()));
      vi.advanceTimersByTime(1000);
      expect(session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "expired" });
      expect(session.getAccessToken()).toBeUndefined();
      session.accept(authenticationResponse("second", new Date(Date.now() + 1000).toISOString()));
      vi.setSystemTime(new Date(Date.now() + 2000)); // Simulate a throttled background timer.
      expect(session.getAccessToken()).toBeUndefined();
      expect(session.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "expired" });
    } finally {
      session.dispose();
    }
  });

  it.each([
    ["blank token", { accessToken: " ", tokenType: "Bearer", expiresAt: "2026-12-01T00:00:00Z" }],
    ["wrong token type", { accessToken: "token", tokenType: "Refresh", expiresAt: "2026-12-01T00:00:00Z" }],
    ["invalid expiry", { accessToken: "token", tokenType: "Bearer", expiresAt: "tomorrow" }],
    ["expired token", { accessToken: "token", tokenType: "Bearer", expiresAt: "2020-01-01T00:00:00Z" }],
    ["missing fields", {}],
    ["null payload", null],
  ])("rejects %s without exposing a bearer", (_label, response) => {
    const session = new AuthSession();
    try {
      expect(() => session.accept(response as never)).toThrowError(/unusable authentication response/i);
      expect(session.getAccessToken()).toBeUndefined();
    } finally {
      session.dispose();
    }
  });

  it("a fresh instance (reload or another tab) cannot recover the previous bearer", () => {
    const first = new AuthSession();
    const second = new AuthSession();
    try {
      first.accept(authenticationResponse());
      expect(first.getAccessToken()).toBeTruthy();
      expect(second.getAccessToken()).toBeUndefined();
      expect(second.getSnapshot()).toEqual({ status: "reauthentication-required", reason: "initial" });
    } finally {
      first.dispose();
      second.dispose();
    }
  });
});
