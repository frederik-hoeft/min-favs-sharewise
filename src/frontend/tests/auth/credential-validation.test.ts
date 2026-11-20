import { describe, expect, it } from "vitest";
import { validateCredentials } from "@/app/(auth)/_components/credentials-validation";

describe('authentication form policy', () => {
  it('checks presence and a basic email shape without normalizing the input', () => {
    expect(validateCredentials('register', '', 'correct horse battery staple').email).toMatch(/enter your email/i);
    expect(validateCredentials('login', 'nonsense', 'long enough').email).toMatch(/valid email/i);
    expect(validateCredentials('login', 'alice@exam ple.org', 'long enough').email).toMatch(/valid email/i);
    expect(validateCredentials('login', 'alice@example.org', 'long enough')).toEqual({});
  });

  it('accepts up to 256 Unicode code points in the email field', () => {
    expect(validateCredentials('login', `${'a'.repeat(244)}@example.org`, 'password').email).toBeUndefined();
    expect(validateCredentials('login', `${'a'.repeat(245)}@example.org`, 'password').email).toMatch(/256/);
  });

  it('enforces 12 Unicode code points on registration, including supplementary characters', () => {
    expect(validateCredentials('register', 'test@example.org', '😀'.repeat(11)).password).toMatch(/too short/i);
    expect(validateCredentials('register', 'test@example.org', '😀'.repeat(12)).password).toBeUndefined();
    // Login must accept older credentials shorter than registration's current minimum.
    expect(validateCredentials('login', 'test@example.org', 'short').password).toBeUndefined();
  });

  it('enforces a 72 UTF-8 byte ceiling rather than JavaScript string length', () => {
    expect(validateCredentials('register', 'test@example.org', 'a'.repeat(72)).password).toBeUndefined();
    expect(validateCredentials('register', 'test@example.org', 'a'.repeat(73)).password).toMatch(/too long/i);
    expect(validateCredentials('login', 'test@example.org', '😀'.repeat(18)).password).toBeUndefined();
    expect(validateCredentials('login', 'test@example.org', '😀'.repeat(19)).password).toMatch(/too long/i);
  });

  it('rejects malformed surrogate pairs and never trims passwords', () => {
    expect(validateCredentials('login', 'test@example.org', '\ud800').password).toMatch(/invalid characters/i);
    expect(validateCredentials('login', 'test@example.org', '\udc00').password).toMatch(/invalid characters/i);
    expect(validateCredentials('login', 'test@example.org', '  ').password).toBeUndefined();
    expect(validateCredentials('register', 'test@example.org', '  '.repeat(6)).password).toBeUndefined();
  });
});
