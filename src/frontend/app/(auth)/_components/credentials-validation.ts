export interface CredentialErrors {
  readonly email?: string;
  readonly password?: string;
}

/** Client-side hints only. The IAM service remains authoritative for email syntax and policy. */
export function validateCredentials(mode: 'login' | 'register', emailInput: string, password: string): CredentialErrors {
  const email = emailInput.trim();
  const errors: { email?: string; password?: string } = {};

  if (!email) {
    errors.email = 'Enter your email address.';
  } else if (Array.from(email).length > 256) {
    errors.email = 'Email must not exceed 256 characters.';
  } else {
    const at = email.lastIndexOf('@');
    if (at <= 0 || at === email.length - 1 || !email.slice(at + 1).includes('.') ||
        /[\p{White_Space}\p{Cc}\p{Cf}]/u.test(email)) {
      errors.email = 'Enter a valid email address.';
    }
  }

  if (!password) {
    errors.password = 'Enter your password.';
  } else {
    // Reject invalid UTF-16 before UTF-8 measurement (TextEncoder replaces lone surrogates).
    let validUnicode = true;
    for (let i = 0; i < password.length; i++) {
      const unit = password.charCodeAt(i);
      if (unit >= 0xd800 && unit <= 0xdbff) {
        if (i + 1 >= password.length || password.charCodeAt(++i) < 0xdc00 || password.charCodeAt(i) > 0xdfff) {
          validUnicode = false;
          break;
        }
      } else if (unit >= 0xdc00 && unit <= 0xdfff) {
        validUnicode = false;
        break;
      }
    }
    if (!validUnicode) {
      errors.password = 'Password contains invalid characters.';
    } else if (new TextEncoder().encode(password).length > 72) {
      errors.password = 'Password must not exceed 72 bytes in UTF-8.';
    } else if (mode === 'register' && Array.from(password).length < 12) {
      errors.password = 'Use at least 12 characters.';
    }
  }

  return errors;
}
