/** Java/JavaScript password limits count UTF-16 code units, not Unicode scalars. */
export function hasValidSurrogatePairs(password: string): boolean {
    for (let i: number = 0; i < password.length; i++) {
        const unit: number = password.charCodeAt(i);
        if (unit >= 0xd800 && unit <= 0xdbff) {
            if (++i >= password.length || password.charCodeAt(i) < 0xdc00 || password.charCodeAt(i) > 0xdfff) {
                return false;
            }
        } else if (unit >= 0xdc00 && unit <= 0xdfff) {
            return false;
        }
    }
    return true;
}

export interface PasswordChangeErrors {
    readonly currentPassword?: string;
    readonly newPassword?: string;
}

/** Client hints matching IAM PasswordPolicy.initial(); the backend remains authoritative. */
export function validatePasswordChange(currentPassword: string, newPassword: string): PasswordChangeErrors {
    const errors: { currentPassword?: string; newPassword?: string } = {};
    if (!currentPassword) errors.currentPassword = 'Enter your current password.';
    else if (currentPassword.length > 256) errors.currentPassword = 'Current password must not exceed 256 characters.';
    else if (!hasValidSurrogatePairs(currentPassword)) errors.currentPassword = 'Current password contains invalid characters.';

    if (!newPassword) errors.newPassword = 'Enter a new password.';
    else if (newPassword.length < 8) errors.newPassword = 'Use at least 8 characters.';
    else if (newPassword.length > 256) errors.newPassword = 'New password must not exceed 256 characters.';
    else if (!hasValidSurrogatePairs(newPassword)) errors.newPassword = 'New password contains invalid characters.';
    else if (currentPassword === newPassword) errors.newPassword = 'Choose a password different from your current password.';
    return errors;
}
