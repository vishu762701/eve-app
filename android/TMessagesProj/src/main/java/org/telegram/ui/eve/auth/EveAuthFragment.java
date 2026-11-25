package org.telegram.ui.eve.auth;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * Primary EVE authentication screen supporting:
 *  - Email & Password Sign-In (with automatic secure Google credential linking when applicable)
 *  - Account Registration (Full Name, Email, Password, Confirm Password)
 *  - Google Sign-In via Android Credential Manager (3-stage fallback + account picker)
 *  - Password Recovery (Firebase sendPasswordResetEmail)
 *  - Email Verification status, resend, and refresh
 *
 * Navigates cleanly to EVE Home whenever Firebase Authentication succeeds, even if cloud
 * profile synchronization is temporarily offline (never traps the user on the auth screen).
 */
public class EveAuthFragment extends BaseFragment {

    public static final int MODE_LOGIN = 0;
    public static final int MODE_REGISTER = 1;
    public static final int MODE_RESET_PASSWORD = 2;
    public static final int MODE_VERIFY_EMAIL = 3;

    private int currentMode = MODE_LOGIN;
    private boolean loading = false;
    private boolean passwordVisible = false;
    private boolean confirmPasswordVisible = false;

    private TextView titleView;
    private TextView subtitleView;
    private TextView statusBanner;

    private LinearLayout fullNameGroup;
    private EditText fullNameField;

    private LinearLayout emailGroup;
    private EditText emailField;

    private LinearLayout passwordGroup;
    private EditText passwordField;
    private TextView passwordToggle;

    private LinearLayout confirmPasswordGroup;
    private EditText confirmPasswordField;
    private TextView confirmPasswordToggle;

    private TextView forgotPasswordLink;
    private FrameLayout primaryButtonContainer;
    private TextView primaryButtonText;
    private ProgressBar primaryProgress;

    private LinearLayout dividerRow;
    private TextView googleButton;
    private TextView secondaryActionButton;
    private TextView tertiaryActionButton;
    private TextView switchModeLink;

    public EveAuthFragment() {
        this(MODE_LOGIN);
    }

    public EveAuthFragment(int initialMode) {
        super();
        this.currentMode = initialMode;
    }

    @Override
    public View createView(Context context) {
        if (actionBar != null) {
            actionBar.setAddToContainer(false);
        }

        int bgColor = Theme.getColor(Theme.key_windowBackgroundWhite);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int hintColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);
        int cardBgColor = Theme.getColor(Theme.key_windowBackgroundGray);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(bgColor);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setPadding(dp(24), dp(56), dp(24), dp(36));
        scrollView.addView(container, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        TextView brandBadge = new TextView(context);
        brandBadge.setText("EVE");
        brandBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        brandBadge.setTypeface(Typeface.DEFAULT_BOLD);
        brandBadge.setTextColor(0xFFFFFFFF);
        brandBadge.setGravity(Gravity.CENTER);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setColor(accentColor);
        badgeBg.setCornerRadius(dp(16));
        brandBadge.setBackground(badgeBg);
        container.addView(brandBadge, LayoutHelper.createLinear(64, 64, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 18));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 24);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setTextColor(textColor);
        titleView.setGravity(Gravity.CENTER);
        container.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        subtitleView.setTextColor(hintColor);
        subtitleView.setGravity(Gravity.CENTER);
        container.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 20));

        statusBanner = new TextView(context);
        statusBanner.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusBanner.setPadding(dp(14), dp(10), dp(14), dp(10));
        statusBanner.setVisibility(View.GONE);
        container.addView(statusBanner, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        fullNameGroup = createFieldGroup(context, "Full Name", textColor, hintColor, cardBgColor);
        fullNameField = (EditText) fullNameGroup.getTag();
        fullNameField.setHint("Enter your full name");
        fullNameField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        fullNameField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        container.addView(fullNameGroup, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        emailGroup = createFieldGroup(context, "Email Address", textColor, hintColor, cardBgColor);
        emailField = (EditText) emailGroup.getTag();
        emailField.setHint("you@example.com");
        emailField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        emailField.setImeOptions(EditorInfo.IME_ACTION_NEXT);
        container.addView(emailGroup, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        passwordGroup = createPasswordFieldGroup(context, "Password", "Enter your password", textColor, hintColor, accentColor, cardBgColor, false);
        passwordField = (EditText) passwordGroup.getTag(0x7f0a0001);
        passwordToggle = (TextView) passwordGroup.getTag(0x7f0a0002);
        passwordToggle.setOnClickListener(v -> {
            passwordVisible = !passwordVisible;
            applyPasswordVisibility(passwordField, passwordToggle, passwordVisible);
        });
        container.addView(passwordGroup, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        confirmPasswordGroup = createPasswordFieldGroup(context, "Confirm Password", "Re-enter your password", textColor, hintColor, accentColor, cardBgColor, true);
        confirmPasswordField = (EditText) confirmPasswordGroup.getTag(0x7f0a0001);
        confirmPasswordToggle = (TextView) confirmPasswordGroup.getTag(0x7f0a0002);
        confirmPasswordToggle.setOnClickListener(v -> {
            confirmPasswordVisible = !confirmPasswordVisible;
            applyPasswordVisibility(confirmPasswordField, confirmPasswordToggle, confirmPasswordVisible);
        });
        container.addView(confirmPasswordGroup, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        forgotPasswordLink = new TextView(context);
        forgotPasswordLink.setText("Forgot Password?");
        forgotPasswordLink.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        forgotPasswordLink.setTextColor(accentColor);
        forgotPasswordLink.setTypeface(Typeface.DEFAULT_BOLD);
        forgotPasswordLink.setGravity(Gravity.END);
        forgotPasswordLink.setPadding(dp(4), dp(2), dp(4), dp(12));
        forgotPasswordLink.setOnClickListener(v -> switchMode(MODE_RESET_PASSWORD));
        container.addView(forgotPasswordLink, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        primaryButtonContainer = new FrameLayout(context);
        GradientDrawable primaryBg = new GradientDrawable();
        primaryBg.setColor(accentColor);
        primaryBg.setCornerRadius(dp(12));
        primaryButtonContainer.setBackground(primaryBg);
        primaryButtonContainer.setOnClickListener(v -> onPrimaryAction());

        primaryButtonText = new TextView(context);
        primaryButtonText.setTextColor(0xFFFFFFFF);
        primaryButtonText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        primaryButtonText.setTypeface(Typeface.DEFAULT_BOLD);
        primaryButtonText.setGravity(Gravity.CENTER);
        primaryButtonContainer.addView(primaryButtonText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        primaryProgress = new ProgressBar(context);
        primaryProgress.setVisibility(View.GONE);
        primaryButtonContainer.addView(primaryProgress, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
        container.addView(primaryButtonContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 6, 0, 16));

        secondaryActionButton = createOutlinedButton(context, textColor, accentColor);
        secondaryActionButton.setVisibility(View.GONE);
        secondaryActionButton.setOnClickListener(v -> onSecondaryAction());
        container.addView(secondaryActionButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 10));

        tertiaryActionButton = createOutlinedButton(context, hintColor, hintColor);
        tertiaryActionButton.setVisibility(View.GONE);
        tertiaryActionButton.setOnClickListener(v -> onTertiaryAction());
        container.addView(tertiaryActionButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, 0, 0, 0, 12));

        dividerRow = new LinearLayout(context);
        dividerRow.setOrientation(LinearLayout.HORIZONTAL);
        dividerRow.setGravity(Gravity.CENTER_VERTICAL);
        View leftLine = new View(context);
        leftLine.setBackgroundColor(Theme.getColor(Theme.key_divider));
        TextView orText = new TextView(context);
        orText.setText("or");
        orText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        orText.setTextColor(hintColor);
        orText.setPadding(dp(12), 0, dp(12), 0);
        View rightLine = new View(context);
        rightLine.setBackgroundColor(Theme.getColor(Theme.key_divider));
        dividerRow.addView(leftLine, new LinearLayout.LayoutParams(0, dp(1), 1f));
        dividerRow.addView(orText, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        dividerRow.addView(rightLine, new LinearLayout.LayoutParams(0, dp(1), 1f));
        container.addView(dividerRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 16));

        googleButton = createOutlinedButton(context, textColor, Theme.getColor(Theme.key_divider));
        googleButton.setText("Continue with Google");
        googleButton.setOnClickListener(v -> onGoogleSignIn());
        container.addView(googleButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 20));

        switchModeLink = new TextView(context);
        switchModeLink.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        switchModeLink.setTextColor(accentColor);
        switchModeLink.setTypeface(Typeface.DEFAULT_BOLD);
        switchModeLink.setGravity(Gravity.CENTER);
        switchModeLink.setPadding(dp(8), dp(8), dp(8), dp(8));
        switchModeLink.setOnClickListener(v -> {
            if (currentMode == MODE_LOGIN) {
                switchMode(MODE_REGISTER);
            } else {
                switchMode(MODE_LOGIN);
            }
        });
        container.addView(switchModeLink, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        applyModeUi();
        fragmentView = root;
        return fragmentView;
    }

    private LinearLayout createFieldGroup(Context context, String label, int textColor, int hintColor, int fieldBgColor) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setTextColor(textColor);
        group.addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        EditText editText = new EditText(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setTextColor(textColor);
        editText.setHintTextColor(hintColor);
        editText.setSingleLine(true);
        editText.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fieldBgColor);
        bg.setCornerRadius(dp(10));
        editText.setBackground(bg);
        group.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        group.setTag(editText);
        return group;
    }

    private LinearLayout createPasswordFieldGroup(
            Context context, String label, String hint, int textColor, int hintColor, int accentColor, int fieldBgColor, boolean isLast) {
        LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setTextColor(textColor);
        group.addView(labelView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        FrameLayout inputWrap = new FrameLayout(context);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fieldBgColor);
        bg.setCornerRadius(dp(10));
        inputWrap.setBackground(bg);

        EditText editText = new EditText(context);
        editText.setHint(hint);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setTextColor(textColor);
        editText.setHintTextColor(hintColor);
        editText.setSingleLine(true);
        editText.setBackground(null);
        editText.setPadding(dp(14), dp(12), dp(64), dp(12));
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        editText.setImeOptions(isLast ? EditorInfo.IME_ACTION_DONE : EditorInfo.IME_ACTION_NEXT);
        inputWrap.addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        TextView toggle = new TextView(context);
        toggle.setText("Show");
        toggle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        toggle.setTextColor(accentColor);
        toggle.setTypeface(Typeface.DEFAULT_BOLD);
        toggle.setGravity(Gravity.CENTER);
        toggle.setPadding(dp(12), 0, dp(14), 0);
        inputWrap.addView(toggle, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.END | Gravity.CENTER_VERTICAL));

        group.addView(inputWrap, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        group.setTag(0x7f0a0001, editText);
        group.setTag(0x7f0a0002, toggle);
        return group;
    }

    private TextView createOutlinedButton(Context context, int textColor, int strokeColor) {
        TextView btn = new TextView(context);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setStroke(dp(1), strokeColor);
        bg.setCornerRadius(dp(12));
        btn.setBackground(bg);
        return btn;
    }

    private void applyPasswordVisibility(EditText field, TextView toggle, boolean visible) {
        int selection = field.getSelectionEnd();
        field.setInputType(visible
                ? (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
                : (InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD));
        if (selection >= 0) {
            field.setSelection(Math.min(selection, field.length()));
        }
        toggle.setText(visible ? "Hide" : "Show");
    }

    private void switchMode(int mode) {
        if (loading) {
            return;
        }
        currentMode = mode;
        clearBanner();
        applyModeUi();
    }

    private void applyModeUi() {
        fullNameGroup.setVisibility(currentMode == MODE_REGISTER ? View.VISIBLE : View.GONE);
        emailGroup.setVisibility(currentMode != MODE_VERIFY_EMAIL ? View.VISIBLE : View.GONE);
        passwordGroup.setVisibility(currentMode == MODE_LOGIN || currentMode == MODE_REGISTER ? View.VISIBLE : View.GONE);
        confirmPasswordGroup.setVisibility(currentMode == MODE_REGISTER ? View.VISIBLE : View.GONE);
        forgotPasswordLink.setVisibility(currentMode == MODE_LOGIN ? View.VISIBLE : View.GONE);
        dividerRow.setVisibility(currentMode == MODE_LOGIN || currentMode == MODE_REGISTER ? View.VISIBLE : View.GONE);
        googleButton.setVisibility(currentMode == MODE_LOGIN || currentMode == MODE_REGISTER ? View.VISIBLE : View.GONE);
        secondaryActionButton.setVisibility(currentMode == MODE_VERIFY_EMAIL ? View.VISIBLE : View.GONE);
        tertiaryActionButton.setVisibility(currentMode == MODE_VERIFY_EMAIL ? View.VISIBLE : View.GONE);
        switchModeLink.setVisibility(currentMode != MODE_VERIFY_EMAIL ? View.VISIBLE : View.GONE);

        if (currentMode == MODE_LOGIN) {
            titleView.setText("Sign in to EVE");
            subtitleView.setText("Access your exam prep, mock tests, and synced progress.");
            primaryButtonText.setText("Sign In");
            switchModeLink.setText("New to EVE? Create an account");
        } else if (currentMode == MODE_REGISTER) {
            titleView.setText("Create EVE Account");
            subtitleView.setText("Start preparing with your personal EVE profile.");
            primaryButtonText.setText("Create Account");
            switchModeLink.setText("Already have an account? Sign In");
        } else if (currentMode == MODE_RESET_PASSWORD) {
            titleView.setText("Reset Password");
            subtitleView.setText("Enter your registered email and we will send a password reset link.");
            primaryButtonText.setText("Send Reset Link");
            switchModeLink.setText("Back to Sign In");
        } else if (currentMode == MODE_VERIFY_EMAIL) {
            EveUserProfile profile = EveAuthManager.getInstance().getCachedProfile();
            String email = profile != null && !TextUtils.isEmpty(profile.email) ? profile.email : "your email address";
            titleView.setText("Verify Your Email");
            subtitleView.setText("We sent a verification link to " + email + ". You can verify now or continue directly to EVE Home.");
            primaryButtonText.setText("I've Verified — Refresh");
            secondaryActionButton.setText("Resend Verification Email");
            tertiaryActionButton.setText("Continue to EVE Home");
        }
    }

    private void onPrimaryAction() {
        if (loading) {
            return;
        }
        AndroidUtilities.hideKeyboard(fragmentView);
        clearBanner();

        if (currentMode == MODE_LOGIN) {
            String email = emailField.getText().toString();
            String password = passwordField.getText().toString();
            setLoading(true);
            EveAuthManager.getInstance().signInWithEmail(email, password, new EveAuthManager.AuthCallback() {
                @Override
                public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                    setLoading(false);
                    navigateAfterAuth(profile, syncOutcome);
                }

                @Override
                public void onError(@NonNull String message, boolean accountDisabled) {
                    setLoading(false);
                    showBanner(message, true);
                }
            });
        } else if (currentMode == MODE_REGISTER) {
            String fullName = fullNameField.getText().toString();
            String email = emailField.getText().toString();
            String password = passwordField.getText().toString();
            String confirmPassword = confirmPasswordField.getText().toString();
            setLoading(true);
            EveAuthManager.getInstance().registerWithEmail(fullName, email, password, confirmPassword, new EveAuthManager.AuthCallback() {
                @Override
                public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                    setLoading(false);
                    navigateAfterAuth(profile, syncOutcome);
                }

                @Override
                public void onError(@NonNull String message, boolean accountDisabled) {
                    setLoading(false);
                    showBanner(message, true);
                }
            });
        } else if (currentMode == MODE_RESET_PASSWORD) {
            String email = emailField.getText().toString();
            setLoading(true);
            EveAuthManager.getInstance().sendPasswordReset(email, new EveAuthManager.SimpleCallback() {
                @Override
                public void onSuccess(@NonNull String message) {
                    setLoading(false);
                    showBanner(message, false);
                }

                @Override
                public void onError(@NonNull String message) {
                    setLoading(false);
                    showBanner(message, true);
                }
            });
        } else if (currentMode == MODE_VERIFY_EMAIL) {
            setLoading(true);
            EveAuthManager.getInstance().reloadUserAndSync(new EveAuthManager.AuthCallback() {
                @Override
                public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                    setLoading(false);
                    if (profile.emailVerified) {
                        navigateToEveHome();
                    } else {
                        showBanner("Email is not verified yet. You can still tap Continue to EVE Home.", false);
                    }
                }

                @Override
                public void onError(@NonNull String message, boolean accountDisabled) {
                    setLoading(false);
                    showBanner(message, true);
                }
            });
        }
    }

    private void onSecondaryAction() {
        if (loading || currentMode != MODE_VERIFY_EMAIL) {
            return;
        }
        setLoading(true);
        EveAuthManager.getInstance().sendEmailVerification(new EveAuthManager.SimpleCallback() {
            @Override
            public void onSuccess(@NonNull String message) {
                setLoading(false);
                showBanner(message, false);
            }

            @Override
            public void onError(@NonNull String message) {
                setLoading(false);
                showBanner(message, true);
            }
        });
    }

    private void onTertiaryAction() {
        if (loading || currentMode != MODE_VERIFY_EMAIL) {
            return;
        }
        navigateToEveHome();
    }

    private void onGoogleSignIn() {
        if (loading) {
            return;
        }
        Activity activity = getParentActivity();
        if (activity == null) {
            showBanner("Unable to launch Google Sign-In right now.", true);
            return;
        }
        AndroidUtilities.hideKeyboard(fragmentView);
        clearBanner();
        setLoading(true);
        EveAuthManager.getInstance().signInWithGoogle(activity, new EveAuthManager.AuthCallback() {
            @Override
            public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                setLoading(false);
                navigateAfterAuth(profile, syncOutcome);
            }

            @Override
            public void onError(@NonNull String message, boolean accountDisabled) {
                setLoading(false);
                if (EveAuthManager.getInstance().hasPendingGoogleLink()) {
                    currentMode = MODE_LOGIN;
                    applyModeUi();
                    String linkEmail = EveAuthManager.getInstance().getPendingLinkEmail();
                    if (!TextUtils.isEmpty(linkEmail)) {
                        emailField.setText(linkEmail);
                    }
                }
                showBanner(message, true);
            }
        });
    }

    private void navigateAfterAuth(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
        EveAuthCoordinator.NavDestination destination = EveAuthCoordinator.resolvePostAuthDestination(
                EveAuthManager.getInstance().isAuthenticated(), syncOutcome.state);
        if (destination == EveAuthCoordinator.NavDestination.EVE_HOME) {
            navigateToEveHome();
        } else {
            showBanner(!TextUtils.isEmpty(syncOutcome.message)
                    ? syncOutcome.message
                    : "Authentication session expired. Please sign in again.", true);
        }
    }

    private void navigateToEveHome() {
        if (getParentLayout() != null) {
            presentFragment(new org.telegram.ui.MainTabsActivity(), true);
        }
    }

    private void setLoading(boolean isLoading) {
        loading = isLoading;
        primaryButtonContainer.setEnabled(!isLoading);
        googleButton.setEnabled(!isLoading);
        secondaryActionButton.setEnabled(!isLoading);
        tertiaryActionButton.setEnabled(!isLoading);
        primaryButtonText.setVisibility(isLoading ? View.INVISIBLE : View.VISIBLE);
        primaryProgress.setVisibility(isLoading ? View.VISIBLE : View.GONE);
    }

    private void showBanner(String message, boolean isError) {
        statusBanner.setText(message);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (isError) {
            bg.setColor(0x22E53935);
            statusBanner.setTextColor(0xFFD32F2F);
        } else {
            bg.setColor(0x222E7D32);
            statusBanner.setTextColor(0xFF2E7D32);
        }
        statusBanner.setBackground(bg);
        statusBanner.setVisibility(View.VISIBLE);
    }

    private void clearBanner() {
        statusBanner.setVisibility(View.GONE);
        statusBanner.setText("");
    }
}
