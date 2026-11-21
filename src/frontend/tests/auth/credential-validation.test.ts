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

  it('enforces 15 to 256 NFC-normalized code points on registration', () => {
    expect(validateCredentials('register', 'test@example.org', '😀'.repeat(14)).password).toMatch(/too short/i);
    expect(validateCredentials('register', 'test@example.org', '😀'.repeat(15)).password).toBeUndefined();
    expect(validateCredentials('register', 'test@example.org', 'a'.repeat(256)).password).toBeUndefined();
    expect(validateCredentials('register', 'test@example.org', 'a'.repeat(257)).password).toMatch(/256/);
    expect(validateCredentials('register', 'test@example.org', 'e\u0301'.repeat(15)).password).toBeUndefined();
    expect(validateCredentials('register', 'test@example.org', 'e\u0301'.repeat(257)).password).toMatch(/256/);
    expect(validateCredentials('login', 'test@example.org', 'short').password).toBeUndefined();
  });

  it('does not impose the old bcrypt byte ceiling on multibyte passwords', () => {
    expect(validateCredentials('register', 'test@example.org', '😀'.repeat(256)).password).toBeUndefined();
    expect(validateCredentials('login', 'test@example.org', '😀'.repeat(257)).password).toMatch(/256/);
    expect(validateCredentials('register', 'test@example.org', 'a'.repeat(2049)).password).toMatch(/256/);
  });

  it('rejects malformed surrogate pairs and never trims passwords', () => {
    expect(validateCredentials('login', 'test@example.org', '\ud800').password).toMatch(/invalid characters/i);
    expect(validateCredentials('login', 'test@example.org', '\udc00').password).toMatch(/invalid characters/i);
    expect(validateCredentials('login', 'test@example.org', 'pass\nword').password).toMatch(/invalid characters/i);
    expect(validateCredentials('login', 'test@example.org', '  ').password).toBeUndefined();
    expect(validateCredentials('register', 'test@example.org', '  '.repeat(8)).password).toBeUndefined();
  });
});
