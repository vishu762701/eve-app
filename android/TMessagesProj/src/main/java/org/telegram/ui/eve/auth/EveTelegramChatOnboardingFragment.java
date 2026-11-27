package org.telegram.ui.eve.auth;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.MainTabsActivity;

import java.util.HashSet;

/**
 * Telegram-native onboarding screen hosted inside MainTabsActivity's Chats tab when the user
 * has not yet linked a Telegram MTProto session.
 */
public class EveTelegramChatOnboardingFragment extends BaseFragment implements MainTabsActivity.TabFragmentDelegate {

    private ScrollView scrollView;
    private LinearLayout contentContainer;
    private RLottieImageView iconView;
    private TextView titleView;
    private TextView descriptionView;
    private TextView connectButton;
    private TextView debugCaptionView;
    private GradientDrawable buttonBg;
    private int bottomInsetPx;

    @Override
    public View createView(Context context) {
        hasOwnBackground = true;
        if (actionBar != null) {
            actionBar.setAddToContainer(false);
            actionBar.setBackButtonDrawable(null);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle(LocaleController.getString(R.string.MainTabsChats));
        }

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);

        contentContainer = new LinearLayout(context);
        contentContainer.setOrientation(LinearLayout.VERTICAL);
        contentContainer.setGravity(Gravity.CENTER);
        contentContainer.setPadding(dp(28), dp(48), dp(28), dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 32));

        iconView = new RLottieImageView(context);
        iconView.setAutoRepeat(false);
        iconView.setAnimation(R.raw.tab_chats, 72, 72);
        iconView.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider),
                PorterDuff.Mode.SRC_IN));
        iconView.playAnimation();
        contentContainer.addView(iconView, LayoutHelper.createLinear(72, 72, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 16));

        titleView = new TextView(context);
        titleView.setText("EVE Chat \u2022 Powered by Telegram");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 21);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        contentContainer.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        descriptionView = new TextView(context);
        descriptionView.setText("Connect your Telegram session for real-time study chats, peer groups, live Telegram polls, and official exam channels.");
        descriptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14.5f);
        descriptionView.setGravity(Gravity.CENTER);
        descriptionView.setLineSpacing(dp(3), 1f);
        descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        contentContainer.addView(descriptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 8, 0, 8, 24));

        connectButton = new TextView(context);
        connectButton.setText("Connect Telegram Chat");
        connectButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
        connectButton.setTypeface(AndroidUtilities.bold());
        connectButton.setGravity(Gravity.CENTER);
        connectButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourceProvider));
        buttonBg = new GradientDrawable();
        buttonBg.setCornerRadius(dp(24));
        buttonBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider));
        connectButton.setBackground(buttonBg);
        ScaleStateListAnimator.apply(connectButton, 0.03f, 1.2f);
        connectButton.setOnClickListener(v ->
                EveTelegramSessionManager.getInstance().launchTelegramLogin(EveTelegramChatOnboardingFragment.this));
        contentContainer.addView(connectButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, Gravity.CENTER_HORIZONTAL, 12, 0, 12, 14));

        debugCaptionView = new TextView(context);
        debugCaptionView.setText("Sign in once with your phone number. Your login code will arrive in your Telegram app and your session will stay signed in across updates.");
        debugCaptionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
        debugCaptionView.setGravity(Gravity.CENTER);
        debugCaptionView.setLineSpacing(dp(2), 1f);
        debugCaptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        contentContainer.addView(debugCaptionView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 8, 0, 8, 0));

        scrollView.addView(contentContainer, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        if (actionBar != null) {
            actionBar.setAddToContainer(false);
            root.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP));
        }

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            bottomInsetPx = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
            updateContainerPadding();
            return insets;
        });

        fragmentView = root;
        return root;
    }

    private void updateContainerPadding() {
        if (contentContainer == null) {
            return;
        }
        int topPad = dp(48) + ActionBar.getCurrentActionBarHeight() + AndroidUtilities.statusBarHeight;
        int bottomPad = dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS + 32) + bottomInsetPx;
        contentContainer.setPadding(dp(28), topPad, dp(28), bottomPad);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (iconView != null) {
            iconView.playAnimation();
        }
    }

    @Override
    public boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
        return true;
    }

    @Override
    public void onParentScrollToTop() {
        if (scrollView != null) {
            scrollView.smoothScrollTo(0, 0);
        }
    }

    @Override
    public void onThemeColorsChanged() {
        super.onThemeColorsChanged();
        if (fragmentView != null) {
            fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourceProvider));
        }
        if (iconView != null) {
            iconView.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider),
                    PorterDuff.Mode.SRC_IN));
        }
        if (titleView != null) {
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourceProvider));
        }
        if (descriptionView != null) {
            descriptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        }
        if (connectButton != null) {
            connectButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourceProvider));
        }
        if (buttonBg != null) {
            buttonBg.setColor(Theme.getColor(Theme.key_featuredStickers_addButton, resourceProvider));
        }
        if (debugCaptionView != null) {
            debugCaptionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourceProvider));
        }
    }

    @Override
    public void addThemeColorKeys(HashSet<Integer> keys) {
        super.addThemeColorKeys(keys);
        keys.add(Theme.key_windowBackgroundWhite);
        keys.add(Theme.key_windowBackgroundWhiteBlackText);
        keys.add(Theme.key_windowBackgroundWhiteGrayText);
        keys.add(Theme.key_featuredStickers_addButton);
        keys.add(Theme.key_featuredStickers_buttonText);
    }
}
