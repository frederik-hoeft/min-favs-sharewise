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
    // Reject malformed UTF-16 and avoid normalizing unbounded input.
    // This is deliberately looser than the 256-normalized-code-point limit.
    const excessiveRawLength = password.length > 2048;
    let validUnicode = true;
    for (let i = 0; !excessiveRawLength && i < password.length; i++) {
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
    if (excessiveRawLength) {
      errors.password = 'Password must not exceed 256 characters.';
    } else if (!validUnicode) {
      errors.password = 'Password contains invalid characters.';
    } else if (/[\p{Cc}\p{Zl}\p{Zp}]/u.test(password)) {
      errors.password = 'Password contains invalid characters.';
    } else if (Array.from(password.normalize('NFC')).length > 256) {
      errors.password = 'Password must not exceed 256 characters.';
    } else if (mode === 'register' && Array.from(password.normalize('NFC')).length < 15) {
      errors.password = 'Password is too short.';
    }
  }

  return errors;
}
