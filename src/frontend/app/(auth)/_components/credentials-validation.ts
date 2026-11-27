import { hasValidSurrogatePairs } from "@/core/identity/password-validation";

export interface CredentialErrors {
  readonly email?: string;
  readonly password?: string;
  readonly displayName?: string;
  readonly confirmPassword?: string;
}

/** Client-side hints only. The IAM service remains authoritative for email syntax and policy. */
export function validateCredentials(mode: 'login' | 'register', emailInput: string, password: string): CredentialErrors {
  const email: string = emailInput.trim();
  const errors: { email?: string; password?: string } = {};

  if (!email) {
    errors.email = 'Enter your email address.';
  } else if (Array.from(email).length > 256) {
    errors.email = 'Email must not exceed 256 characters.';
  } else {
    const at: number = email.lastIndexOf('@');
    if (at <= 0 || at === email.length - 1 || !email.slice(at + 1).includes('.') ||
        /[\p{White_Space}\p{Cc}\p{Cf}]/u.test(email)) {
      errors.email = 'Enter a valid email address.';
    }
  }

  if (!password) {
    errors.password = 'Enter your password.';
  } else if (password.length > 256) {
    errors.password = 'Password must not exceed 256 characters.';
  } else if (mode === 'register' && password.length < 8) {
    errors.password = 'Password is too short.';
  } else if (!hasValidSurrogatePairs(password)) {
    errors.password = 'Password contains invalid characters.';
  }

  return errors;
}
