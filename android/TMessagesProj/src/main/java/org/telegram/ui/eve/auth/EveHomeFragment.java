package org.telegram.ui.eve.auth;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

/**
 * EVE Home screen displayed for authenticated Firebase sessions.
 * Accurately reflects all cloud synchronization states:
 *   - Signed in
 *   - Syncing
 *   - Cloud synchronized (with server-confirmed EVE ID)
 *   - Offline / retry required (with safe diagnostic details and auto-retry when connectivity returns)
 *   - Authentication expired
 *   - Account disabled
 *
 * The Sync Profile button performs a real forced-token sync request with loading indicator,
 * duplicate-request protection, and explicit success/error feedback.
 */
public class EveHomeFragment extends BaseFragment implements EveAuthManager.SessionListener {

    private TextView greetingView;
    private TextView emailView;
    private TextView eveIdView;
    private TextView providerView;
    private TextView syncBadgeView;
    private TextView syncDetailView;
    private LinearLayout verifyCard;
    private TextView verifyMessageView;
    private TextView actionFeedbackView;
    private FrameLayout syncButtonContainer;
    private TextView syncButtonText;
    private ProgressBar syncButtonProgress;
    private boolean syncRequestActive = false;

    @Override
    public View createView(Context context) {
        if (actionBar != null) {
            actionBar.setTitle("Profile & Account");
            actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
            actionBar.setActionBarMenuOnItemClick(new org.telegram.ui.ActionBar.ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    }
                }
            });
        }

        int bgColor = Theme.getColor(Theme.key_windowBackgroundGray);
        int cardColor = Theme.getColor(Theme.key_windowBackgroundWhite);
        int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        int subTextColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText);
        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton);

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(bgColor);

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(20), dp(20), dp(20), dp(32));
        scrollView.addView(container, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));

        // Feedback Banner
        actionFeedbackView = new TextView(context);
        actionFeedbackView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        actionFeedbackView.setPadding(dp(14), dp(10), dp(14), dp(10));
        actionFeedbackView.setVisibility(View.GONE);
        container.addView(actionFeedbackView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        // Account Summary Card
        LinearLayout profileCard = createCard(context, cardColor);
        greetingView = new TextView(context);
        greetingView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        greetingView.setTypeface(Typeface.DEFAULT_BOLD);
        greetingView.setTextColor(textColor);
        profileCard.addView(greetingView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        emailView = new TextView(context);
        emailView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        emailView.setTextColor(subTextColor);
        profileCard.addView(emailView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        syncBadgeView = new TextView(context);
        syncBadgeView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        syncBadgeView.setTypeface(Typeface.DEFAULT_BOLD);
        syncBadgeView.setPadding(dp(10), dp(4), dp(10), dp(4));
        profileCard.addView(syncBadgeView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        eveIdView = createMetaRow(context, textColor);
        profileCard.addView(eveIdView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        providerView = createMetaRow(context, textColor);
        profileCard.addView(providerView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        syncDetailView = createMetaRow(context, subTextColor);
        profileCard.addView(syncDetailView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        container.addView(profileCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Email Verification Card (shown only when Email/Password account is unverified)
        verifyCard = createCard(context, cardColor);
        verifyCard.setVisibility(View.GONE);

        TextView verifyTitle = new TextView(context);
        verifyTitle.setText("Email Verification Recommended");
        verifyTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        verifyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        verifyTitle.setTextColor(0xFFE65100);
        verifyCard.addView(verifyTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        verifyMessageView = new TextView(context);
        verifyMessageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        verifyMessageView.setTextColor(subTextColor);
        verifyCard.addView(verifyMessageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        LinearLayout verifyButtons = new LinearLayout(context);
        verifyButtons.setOrientation(LinearLayout.HORIZONTAL);

        TextView resendBtn = createActionButton(context, "Resend Email", accentColor, 0xFFFFFFFF);
        resendBtn.setOnClickListener(v -> EveAuthManager.getInstance().sendEmailVerification(new EveAuthManager.SimpleCallback() {
            @Override
            public void onSuccess(@NonNull String message) {
                showFeedback(message, false);
            }

            @Override
            public void onError(@NonNull String message) {
                showFeedback(message, true);
            }
        }));
        verifyButtons.addView(resendBtn, new LinearLayout.LayoutParams(0, dp(42), 1f));

        View spacer = new View(context);
        verifyButtons.addView(spacer, LayoutHelper.createLinear(10, 1));

        TextView refreshBtn = createActionButton(context, "Refresh", 0x00000000, accentColor);
        GradientDrawable refreshBg = new GradientDrawable();
        refreshBg.setStroke(dp(1), accentColor);
        refreshBg.setCornerRadius(dp(10));
        refreshBtn.setBackground(refreshBg);
        refreshBtn.setOnClickListener(v -> triggerCloudSync(true));
        verifyButtons.addView(refreshBtn, new LinearLayout.LayoutParams(0, dp(42), 1f));

        verifyCard.addView(verifyButtons, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        container.addView(verifyCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 14));

        // Cloud Sync & Account Controls Card
        LinearLayout actionsCard = createCard(context, cardColor);

        syncButtonContainer = new FrameLayout(context);
        GradientDrawable syncBg = new GradientDrawable();
        syncBg.setColor(accentColor);
        syncBg.setCornerRadius(dp(10));
        syncButtonContainer.setBackground(syncBg);
        syncButtonContainer.setOnClickListener(v -> triggerCloudSync(true));

        syncButtonText = new TextView(context);
        syncButtonText.setText("Sync Profile with EVE Cloud");
        syncButtonText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        syncButtonText.setTypeface(Typeface.DEFAULT_BOLD);
        syncButtonText.setTextColor(0xFFFFFFFF);
        syncButtonText.setGravity(Gravity.CENTER);
        syncButtonContainer.addView(syncButtonText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        syncButtonProgress = new ProgressBar(context);
        syncButtonProgress.setVisibility(View.GONE);
        syncButtonContainer.addView(syncButtonProgress, LayoutHelper.createFrame(22, 22, Gravity.CENTER));
        actionsCard.addView(syncButtonContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, 0, 0, 0, 12));

        TextView signOutBtn = createActionButton(context, "Sign Out", 0x18E53935, 0xFFD32F2F);
        signOutBtn.setOnClickListener(v -> confirmSignOut());
        actionsCard.addView(signOutBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));

        container.addView(actionsCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        EveAuthManager mgr = EveAuthManager.getInstance();
        bindProfile(mgr.getCachedProfile(), mgr.getCurrentSyncState(), mgr.getLastSyncDiagnostic());
        fragmentView = root;
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        EveAuthManager mgr = EveAuthManager.getInstance();
        mgr.addSessionListener(this);
        if (!mgr.isAuthenticated()) {
            presentFragment(new EveAuthFragment(), true);
            return;
        }
        EveUserProfile cached = mgr.getCachedProfile();
        if (cached == null || !cached.syncedWithBackend || TextUtils.isEmpty(cached.eveId)) {
            triggerCloudSync(false);
        } else {
            bindProfile(cached, mgr.getCurrentSyncState(), mgr.getLastSyncDiagnostic());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        EveAuthManager.getInstance().removeSessionListener(this);
    }

    @Override
    public void onSessionChanged(
            boolean authenticated,
            @Nullable EveUserProfile profile,
            @NonNull EveAuthCoordinator.SyncState syncState,
            @NonNull String diagnosticMessage) {
        if (!authenticated) {
            if (getParentLayout() != null) {
                presentFragment(new EveAuthFragment(), true);
            }
            return;
        }
        bindProfile(profile, syncState, diagnosticMessage);
    }

    private void triggerCloudSync(boolean userInitiated) {
        if (syncRequestActive || EveAuthManager.getInstance().isSyncInFlight()) {
            return;
        }
        setSyncButtonLoading(true);
        if (userInitiated) {
            actionFeedbackView.setVisibility(View.GONE);
        }
        EveAuthManager.getInstance().reloadUserAndSync(new EveAuthManager.AuthCallback() {
            @Override
            public void onSuccess(@NonNull EveUserProfile profile, @NonNull EveAuthCoordinator.SyncOutcome syncOutcome) {
                setSyncButtonLoading(false);
                bindProfile(profile, syncOutcome.state, syncOutcome.message);
                if (syncOutcome.state == EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED) {
                    if (userInitiated) {
                        showFeedback(syncOutcome.message, false);
                    }
                } else if (syncOutcome.state == EveAuthCoordinator.SyncState.AUTH_EXPIRED) {
                    showFeedback(syncOutcome.message, true);
                } else if (syncOutcome.state == EveAuthCoordinator.SyncState.OFFLINE_RETRY_REQUIRED) {
                    showFeedback(syncOutcome.message, true);
                }
            }

            @Override
            public void onError(@NonNull String message, boolean accountDisabled) {
                setSyncButtonLoading(false);
                if (accountDisabled || !EveAuthManager.getInstance().isAuthenticated()) {
                    presentFragment(new EveAuthFragment(), true);
                } else {
                    showFeedback(message, true);
                }
            }
        });
    }

    private void setSyncButtonLoading(boolean loading) {
        syncRequestActive = loading;
        if (syncButtonContainer != null) {
            syncButtonContainer.setEnabled(!loading);
            syncButtonContainer.setAlpha(loading ? 0.7f : 1.0f);
        }
        if (syncButtonText != null) {
            syncButtonText.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        }
        if (syncButtonProgress != null) {
            syncButtonProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        }
    }

    private void bindProfile(
            @Nullable EveUserProfile profile,
            @NonNull EveAuthCoordinator.SyncState syncState,
            @Nullable String diagnosticMessage) {
        if (profile == null || greetingView == null) {
            return;
        }
        greetingView.setText("Welcome, " + profile.displayName);
        emailView.setText(!TextUtils.isEmpty(profile.email) ? profile.email : "No email address");
        eveIdView.setText("EVE ID: " + (!TextUtils.isEmpty(profile.eveId) ? profile.eveId : "Pending cloud sync"));
        providerView.setText("Sign-in method: " + ("google.com".equals(profile.authProvider) ? "Google" : "Email & Password"));

        applySyncStateBadge(syncState, profile, diagnosticMessage);

        if (!profile.emailVerified && "password".equals(profile.authProvider)) {
            verifyCard.setVisibility(View.VISIBLE);
            verifyMessageView.setText("Your email (" + profile.email + ") is not verified yet. Check your inbox or resend the verification link.");
        } else {
            verifyCard.setVisibility(View.GONE);
        }
    }

    private void applySyncStateBadge(
            @NonNull EveAuthCoordinator.SyncState state,
            @NonNull EveUserProfile profile,
            @Nullable String diagnosticMessage) {
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(dp(8));

        if (state == EveAuthCoordinator.SyncState.CLOUD_SYNCHRONIZED && profile.syncedWithBackend && !TextUtils.isEmpty(profile.eveId)) {
            syncBadgeView.setText("Cloud synchronized");
            syncBadgeView.setTextColor(0xFF1B5E20);
            pill.setColor(0x222E7D32);
            syncDetailView.setText("Cloud sync: Connected to EVE Cloud (" + profile.eveId + ")");
        } else if (state == EveAuthCoordinator.SyncState.SYNCING) {
            syncBadgeView.setText("Syncing...");
            syncBadgeView.setTextColor(0xFF0D47A1);
            pill.setColor(0x221976D2);
            syncDetailView.setText("Cloud sync: Synchronizing profile with EVE Cloud...");
        } else if (state == EveAuthCoordinator.SyncState.AUTH_EXPIRED) {
            syncBadgeView.setText("Authentication expired");
            syncBadgeView.setTextColor(0xFFB71C1C);
            pill.setColor(0x22D32F2F);
            syncDetailView.setText(!TextUtils.isEmpty(diagnosticMessage) ? diagnosticMessage : "Please sign in again to refresh your session.");
        } else if (state == EveAuthCoordinator.SyncState.ACCOUNT_DISABLED) {
            syncBadgeView.setText("Account disabled");
            syncBadgeView.setTextColor(0xFFB71C1C);
            pill.setColor(0x22D32F2F);
            syncDetailView.setText("This account has been disabled.");
        } else if (state == EveAuthCoordinator.SyncState.OFFLINE_RETRY_REQUIRED) {
            syncBadgeView.setText("Offline / retry required");
            syncBadgeView.setTextColor(0xFFE65100);
            pill.setColor(0x22F57C00);
            syncDetailView.setText(!TextUtils.isEmpty(diagnosticMessage)
                    ? diagnosticMessage
                    : "Cloud sync pending. Auto-retry is queued when connectivity returns.");
        } else {
            syncBadgeView.setText("Signed in");
            syncBadgeView.setTextColor(0xFF0D47A1);
            pill.setColor(0x221976D2);
            syncDetailView.setText("Cloud sync: Ready to synchronize");
        }
        syncBadgeView.setBackground(pill);
    }

    private void confirmSignOut() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Sign Out");
        builder.setMessage("Are you sure you want to sign out of your EVE account on this device?");
        builder.setPositiveButton("Sign Out", (dialog, which) ->
                EveAuthManager.getInstance().signOut(getParentActivity(), () -> {
                    if (getParentLayout() != null) {
                        presentFragment(new EveAuthFragment(), true);
                    }
                }));
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void showFeedback(String message, boolean isError) {
        actionFeedbackView.setText(message);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        if (isError) {
            bg.setColor(0x22E53935);
            actionFeedbackView.setTextColor(0xFFD32F2F);
        } else {
            bg.setColor(0x222E7D32);
            actionFeedbackView.setTextColor(0xFF2E7D32);
        }
        actionFeedbackView.setBackground(bg);
        actionFeedbackView.setVisibility(View.VISIBLE);
    }

    private static LinearLayout createCard(Context context, int bgColor) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(14));
        card.setBackground(bg);
        return card;
    }

    private static TextView createMetaRow(Context context, int color) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        tv.setTextColor(color);
        return tv;
    }

    private static TextView createActionButton(Context context, String text, int bgColor, int textColor) {
        TextView btn = new TextView(context);
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setTextColor(textColor);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(10));
        btn.setBackground(bg);
        return btn;
    }
}
