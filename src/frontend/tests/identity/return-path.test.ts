import { describe, expect, it } from 'vitest';
import { safeAppReturnPath } from '@/lib/navigation/return-path';

describe('login return path validation', () => {
  it.each([
    ['/app', '/app'],
    ['/app/groups/123?tab=expenses', '/app/groups/123?tab=expenses'],
    ['/app/../admin', '/app'],
    ['//evil.example/app', '/app'],
    ['https://evil.example/app', '/app'],
    ['/app/%2f..%2fadmin', '/app'],
    ['/app\\evil', '/app'],
    ['/application', '/app'],
    [null, '/app'],
  ])('maps %s to a validated application location', (input, expected) => {
    expect(safeAppReturnPath(input)).toBe(expected);
  });
});
