package org.telegram.ui.eve.auth;

import java.util.regex.Pattern;

/**
 * Pure-Java authentication input validator and Firebase/Credential Manager error mapper.
 * Kept free of Android framework dependencies so every validation and error rule can be
 * executed directly in JVM unit tests.
 */
public final class EveAuthValidator {

    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final int MAX_PASSWORD_LENGTH = 128;
    public static final int MIN_NAME_LENGTH = 2;
    public static final int MAX_NAME_LENGTH = 100;

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
    );

    private EveAuthValidator() {
    }

    public static final class ValidationResult {
        public final boolean valid;
        public final String errorMessage;

        private ValidationResult(boolean valid, String errorMessage) {
            this.valid = valid;
            this.errorMessage = errorMessage;
        }

        public static ValidationResult ok() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult fail(String message) {
            return new ValidationResult(false, message);
        }
    }

    public static ValidationResult validateEmail(String email) {
        if (email == null || email.trim().isEmpty()) {
            return ValidationResult.fail("Please enter your email address.");
        }
        String trimmed = email.trim();
        if (trimmed.length() > 254 || trimmed.contains("..") || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            return ValidationResult.fail("Please enter a valid email address.");
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateLoginPassword(String password) {
        if (password == null || password.isEmpty()) {
            return ValidationResult.fail("Please enter your password.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            return ValidationResult.fail("Password is too long.");
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateRegistrationPassword(String password) {
        if (password == null || password.isEmpty()) {
            return ValidationResult.fail("Please enter a password.");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return ValidationResult.fail("Password must be at least " + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (password.length() > MAX_PASSWORD_LENGTH) {
            return ValidationResult.fail("Password must not exceed " + MAX_PASSWORD_LENGTH + " characters.");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            return ValidationResult.fail("Password must include at least one letter and one number.");
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateConfirmPassword(String password, String confirmPassword) {
        if (confirmPassword == null || confirmPassword.isEmpty()) {
            return ValidationResult.fail("Please confirm your password.");
        }
        if (!confirmPassword.equals(password)) {
            return ValidationResult.fail("Passwords do not match.");
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateFullName(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return ValidationResult.fail("Please enter your full name.");
        }
        String trimmed = fullName.trim();
        if (trimmed.length() < MIN_NAME_LENGTH) {
            return ValidationResult.fail("Full name must be at least " + MIN_NAME_LENGTH + " characters.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            return ValidationResult.fail("Full name must not exceed " + MAX_NAME_LENGTH + " characters.");
        }
        return ValidationResult.ok();
    }

    public static ValidationResult validateRegistrationInput(
            String fullName, String email, String password, String confirmPassword) {
        ValidationResult nameResult = validateFullName(fullName);
        if (!nameResult.valid) {
            return nameResult;
        }
        ValidationResult emailResult = validateEmail(email);
        if (!emailResult.valid) {
            return emailResult;
        }
        ValidationResult passwordResult = validateRegistrationPassword(password);
        if (!passwordResult.valid) {
            return passwordResult;
        }
        return validateConfirmPassword(password, confirmPassword);
    }

    public static ValidationResult validateLoginInput(String email, String password) {
        ValidationResult emailResult = validateEmail(email);
        if (!emailResult.valid) {
            return emailResult;
        }
        return validateLoginPassword(password);
    }

    /**
     * Validates that the configured Google Sign-In server client ID is a Web OAuth client ID
     * (never empty and never an Android-only package string).
     */
    public static boolean isValidWebOAuthClientId(String clientId) {
        if (clientId == null) {
            return false;
        }
        String trimmed = clientId.trim();
        return !trimmed.isEmpty() && trimmed.endsWith(".apps.googleusercontent.com");
    }

    public static String mapAuthErrorMessage(String errorCode, String fallbackMessage) {
        if (errorCode != null) {
            switch (errorCode) {
                case "ERROR_INVALID_EMAIL":
                    return "The email address is not valid.";
                case "ERROR_WRONG_PASSWORD":
                case "ERROR_INVALID_CREDENTIAL":
                    return "Invalid email or password. Please check your credentials and try again.";
                case "ERROR_USER_NOT_FOUND":
                    return "No EVE account was found for this email address.";
                case "ERROR_USER_DISABLED":
                    return "This account has been disabled. Please contact EVE support.";
                case "ERROR_EMAIL_ALREADY_IN_USE":
                    return "An account with this email already exists. Try signing in instead.";
                case "ERROR_WEAK_PASSWORD":
                    return "Please choose a stronger password (at least 8 characters with letters and numbers).";
                case "ERROR_TOO_MANY_REQUESTS":
                    return "Too many attempts. Please wait a moment and try again.";
                case "ERROR_NETWORK_REQUEST_FAILED":
                    return "Network error. Please check your internet connection and try again.";
                case "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL":
                    return "An EVE account already exists with this email using Email & Password. Sign in with your email and password to link Google securely.";
                case "ERROR_CREDENTIAL_CANCELLED":
                    return "Sign-in was cancelled.";
                case "ERROR_NO_GOOGLE_CREDENTIAL":
                    return "No selectable Google account was returned. Add a Google account in Android Settings -> Passwords & accounts, or verify that this build's SHA-1 is registered in Firebase Console.";
                case "ERROR_MISSING_WEB_CLIENT_ID":
                    return "Google Sign-In is not configured for this build. Verify Firebase Console OAuth settings.";
                default:
                    break;
            }
        }
        if (fallbackMessage != null && !fallbackMessage.trim().isEmpty()) {
            String lower = fallbackMessage.toLowerCase();
            if (lower.contains("28436") || lower.contains("16:") || lower.contains("developer_error") || lower.contains("reauth")) {
                return "Google Sign-In configuration mismatch (verify app SHA-1 and Web OAuth Client ID in Firebase Console).";
            }
            if (lower.contains("network") || lower.contains("timeout") || lower.contains("unable to resolve host")) {
                return "Network error. Please check your internet connection and try again.";
            }
            if (lower.contains("disabled")) {
                return "This account has been disabled. Please contact EVE support.";
            }
            if (lower.contains("credential") && lower.contains("incorrect")) {
                return "Invalid email or password. Please check your credentials and try again.";
            }
        }
        return "Authentication failed. Please try again.";
    }

    public static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        if (!authorizationHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authorizationHeader.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }
}
