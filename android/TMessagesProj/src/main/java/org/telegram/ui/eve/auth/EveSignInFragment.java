package org.telegram.ui.eve.auth;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LanguageSelectActivity;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.eve.exams.EveExamRepository;
import org.telegram.ui.eve.notifications.EveNotificationHelper;

/**
 * EVE Google / Firebase Sign-In entry screen built with native Telegram BaseFragment + Theme primitives.
 * Completely replaces Telegram phone-number / OTP login for EVE users.
 */
public class EveSignInFragment extends BaseFragment {

    private ProgressBar progressBar;
    private TextView signInButton;
    private TextView errorView;
    private boolean signingIn = false;

    @Override
    public View createView(Context context) {
        if (actionBar != null) {
            actionBar.setAddToContainer(false);
        }

        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(56), AndroidUtilities.dp(28), AndroidUtilities.dp(32));
        scroll.addView(root, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        ImageView logo = new ImageView(context);
        logo.setImageResource(R.drawable.eve_splash_320);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(logo, LayoutHelper.createLinear(112, 112, Gravity.CENTER_HORIZONTAL, 0, 24, 0, 20));

        TextView title = new TextView(context);
        title.setText(LocaleController.getString(R.string.AppName));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
        title.setTypeface(AndroidUtilities.bold());
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        root.addView(title, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        TextView subtitle = new TextView(context);
        subtitle.setText(LocaleController.getString(R.string.EveCloudSignInSubtitle));
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        subtitle.setGravity(Gravity.CENTER);
        subtitle.setLineSpacing(AndroidUtilities.dp(3), 1f);
        subtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        root.addView(subtitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 28));

        errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        errorView.setGravity(Gravity.CENTER);
        errorView.setTextColor(0xFFD93025);
        errorView.setVisibility(View.GONE);
        root.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        progressBar = new ProgressBar(context);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, LayoutHelper.createLinear(36, 36, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 14));

        signInButton = new TextView(context);
        signInButton.setText(LocaleController.getString(R.string.EveContinueWithGoogle));
        signInButton.setGravity(Gravity.CENTER);
        signInButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        signInButton.setTypeface(AndroidUtilities.bold());
        signInButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setCornerRadius(AndroidUtilities.dp(24));
        btnBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        signInButton.setBackground(btnBg);
        signInButton.setOnClickListener(v -> triggerGoogleSignIn());
        root.addView(signInButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 16));

        TextView langBtn = new TextView(context);
        langBtn.setText(LocaleController.getString(R.string.SettingsLanguage) + ": " + LocaleController.getCurrentLanguageName());
        langBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        langBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        langBtn.setGravity(Gravity.CENTER);
        langBtn.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        langBtn.setOnClickListener(v -> presentFragment(new LanguageSelectActivity()));
        root.addView(langBtn, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 4, 0, 12));

        TextView disclosure = new TextView(context);
        disclosure.setText(LocaleController.getString(R.string.EveThirdPartyApiDisclosure));
        disclosure.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        disclosure.setGravity(Gravity.CENTER);
        disclosure.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        disclosure.setOnClickListener(v -> {
            Activity act = getParentActivity();
            if (act != null) {
                new AlertDialog.Builder(act)
                        .setTitle(LocaleController.getString(R.string.PrivacyPolicy))
                        .setMessage(LocaleController.getString(R.string.EvePrivacyPolicyBody))
                        .setPositiveButton(LocaleController.getString(R.string.OK), null)
                        .show();
            }
        });
        root.addView(disclosure, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 0));

        fragmentView = scroll;
        return scroll;
    }

    private void triggerGoogleSignIn() {
        Activity activity = getParentActivity();
        if (activity == null || signingIn) {
            return;
        }
        signingIn = true;
        errorView.setVisibility(View.GONE);
        progressBar.setVisibility(View.VISIBLE);
        signInButton.setEnabled(false);
        signInButton.setAlpha(0.6f);

        EveAuthManager.getInstance().signInWithGoogle(activity, new EveAuthManager.AuthCallback() {
            @Override
            public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                signingIn = false;
                if (getParentActivity() == null) {
                    return;
                }
                progressBar.setVisibility(View.GONE);
                signInButton.setEnabled(true);
                signInButton.setAlpha(1f);
                EveAuthManager.getInstance().ensureLocalUiUser(currentAccount);
                EveNotificationHelper.syncFcmTokenIfSignedIn();
                EveExamRepository.getInstance().loadExams(true);
                presentFragment(new MainTabsActivity(), true);
            }

            @Override
            public void onError(@NonNull String message, boolean accountDisabled) {
                signingIn = false;
                if (getParentActivity() == null) {
                    return;
                }
                progressBar.setVisibility(View.GONE);
                signInButton.setEnabled(true);
                signInButton.setAlpha(1f);
                errorView.setText(message);
                errorView.setVisibility(View.VISIBLE);
            }
        });
    }
}
