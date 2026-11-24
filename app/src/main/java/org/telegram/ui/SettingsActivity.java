/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2024.
 */

package org.telegram.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.math.MathUtils;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;

public class SettingsActivity extends BaseFragment {

    public static final int ROW_ACCOUNT = 1;
    public static final int ROW_CHAT = 2;
    public static final int ROW_PRIVACY = 3;
    public static final int ROW_NOTIFICATIONS = 5;
    public static final int ROW_DATA = 6;
    public static final int ROW_FOLDERS = 7;
    public static final int ROW_DEVICES = 8;
    public static final int ROW_POWER = 9;
    public static final int ROW_LANGUAGE = 10;
    public static final int ROW_ASK = 17;
    public static final int ROW_FAQ = 18;
    public static final int ROW_POLICY = 19;
    public static final int ROW_TARGET_EXAMS = 20;
    public static final int ROW_FEATURES = 23;

    public static final int MENU_SEARCH = 0;
    public static final int MENU_QR = 1;
    public static final int MENU_OTHER = 2;
    public static final int MENU_LOG_OUT = 3;
    public static final int MENU_DELETE_ACCOUNT = 4;

    private com.eve.app.ui.settings.TelegramSettingsDelegate eveDelegate; // EVE-PATCH: wire Eve CUT-INTERFACE TelegramSettingsDelegate

    private UniversalRecyclerView listView;
    private FrameLayout topView;
    private TextView titleView;
    private TextView subtitleView;
    private ImageView avatarView;
    private AvatarDrawable avatarDrawable;
    private FrameLayout avatarContainer;
    private FrameLayout cameraButton;
    private FrameLayout cameraBackground;
    private boolean actionBarVisible;
    private ValueAnimator actionBarVisibleAnimator;
    private TextView versionView;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem otherItem;
    private String searchQuery = null;

    public void setEveDelegate(com.eve.app.ui.settings.TelegramSettingsDelegate delegate) {
        this.eveDelegate = delegate;
    }

    public com.eve.app.ui.settings.TelegramSettingsDelegate getEveDelegate() {
        return eveDelegate;
    }

    @Override
    public View createView(Context context) {
        setHasOwnBackground(true);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(LocaleController.getString(R.string.Settings));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_QR) {
                    if (eveDelegate != null) {
                        eveDelegate.onQrClicked();
                    }
                } else if (id == MENU_LOG_OUT) {
                    if (eveDelegate != null) {
                        eveDelegate.onLogOutClicked();
                    }
                } else if (id == MENU_DELETE_ACCOUNT) {
                    if (eveDelegate != null) {
                        eveDelegate.onDeleteAccountClicked();
                    }
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        searchItem = menu.addItem(MENU_SEARCH, R.drawable.outline_header_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
            @Override
            public void onSearchExpand() {
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(false);
                }
                updateActionBarVisible();
            }

            @Override
            public void onSearchCollapse() {
                searchQuery = null;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(false);
                }
                updateActionBarVisible();
            }

            @Override
            public void onTextChanged(EditText editText) {
                searchQuery = editText != null && editText.getText() != null ? editText.getText().toString().trim().toLowerCase(Locale.ROOT) : null;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(false);
                }
            }
        });
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));

        ActionBarMenuItem qrItem = menu.addItem(MENU_QR, R.drawable.msg_qrcode);
        qrItem.setContentDescription(LocaleController.getString(R.string.AuthAnotherClientScan));

        otherItem = menu.addItem(MENU_OTHER, R.drawable.ic_ab_other);
        otherItem.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        otherItem.addSubItem(MENU_LOG_OUT, R.drawable.msg_leave, LocaleController.getString(R.string.LogOut));
        otherItem.addSubItem(MENU_DELETE_ACCOUNT, R.drawable.msg_block, LocaleController.getString(R.string.DeleteAccount));

        FrameLayout contentView = new FrameLayout(context);
        contentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        topView = new FrameLayout(context);

        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setOrientation(LinearLayout.VERTICAL);
        headerLayout.setGravity(Gravity.CENTER_HORIZONTAL);
        headerLayout.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(14));

        avatarContainer = new FrameLayout(context);
        avatarContainer.setContentDescription(LocaleController.getString(R.string.AccDescrProfilePicture));
        avatarDrawable = new AvatarDrawable();
        avatarView = new ImageView(context);
        avatarView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        avatarView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        avatarView.setClipToOutline(true);
        avatarContainer.addView(avatarView, LayoutHelper.createFrame(90, 90, Gravity.CENTER));

        cameraButton = new FrameLayout(context);
        cameraButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(32), Theme.getColor(Theme.key_windowBackgroundGray)));
        cameraButton.setPadding(AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2), AndroidUtilities.dp(2));
        cameraBackground = new FrameLayout(context);
        cameraBackground.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(28), Theme.getColor(Theme.key_featuredStickers_addButton)));
        ImageView cameraImageView = new ImageView(context);
        cameraImageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        cameraImageView.setImageResource(com.eve.app.R.drawable.ic_camera);
        cameraImageView.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        cameraBackground.addView(cameraImageView, LayoutHelper.createFrame(18, 18, Gravity.CENTER));
        cameraButton.addView(cameraBackground, LayoutHelper.createFrame(28, 28, Gravity.CENTER));
        avatarContainer.addView(cameraButton, LayoutHelper.createFrame(32, 32, Gravity.BOTTOM | Gravity.RIGHT));

        avatarContainer.setOnClickListener(v -> {
            if (eveDelegate != null) {
                eveDelegate.onAvatarClicked();
            }
        });
        headerLayout.addView(avatarContainer, LayoutHelper.createLinear(96, 96, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 10));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        headerLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 4));

        subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subtitleView.setSingleLine(true);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        headerLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL, 16, 0, 16, 0));

        topView.addView(headerLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        versionView = new TextView(context);
        versionView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        versionView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText4));
        versionView.setPadding(AndroidUtilities.dp(21), AndroidUtilities.dp(16), AndroidUtilities.dp(21), AndroidUtilities.dp(24));
        versionView.setGravity(Gravity.CENTER);
        versionView.setText(eveDelegate != null ? eveDelegate.getVersionString() : "EVE Android");

        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, this::onLongClick);
        listView.setSections();
        listView.setSectionsDrawBackground(true);
        listView.setClipToPadding(false);
        listView.setPadding(0, 0, 0, AndroidUtilities.dp(24));
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateActionBarVisible();
                updateAvatarHide();
            }
        });
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        ViewCompat.setOnApplyWindowInsetsListener(contentView, (v, insets) -> {
            if (listView != null) {
                int bottomInset = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime()).bottom;
                listView.setPadding(0, 0, 0, AndroidUtilities.dp(24) + bottomInset);
            }
            return insets;
        });

        updateUserInfo();
        listView.adapter.update(false);
        updateActionBarVisible(true, false);

        return fragmentView = contentView;
    }

    public void updateUserInfo() {
        if (eveDelegate != null) {
            String displayName = eveDelegate.getUserDisplayName();
            String subtitle = eveDelegate.getUserSubtitle();
            long uidHash = eveDelegate.getUserUidHash();
            Drawable userPhoto = eveDelegate.getUserPhoto();

            if (titleView != null) {
                titleView.setText(displayName != null ? displayName : "");
            }
            if (subtitleView != null) {
                subtitleView.setText(subtitle != null ? subtitle : "");
            }
            if (versionView != null) {
                versionView.setText(eveDelegate.getVersionString());
            }
            if (avatarView != null) {
                if (userPhoto != null) {
                    avatarView.setImageDrawable(userPhoto);
                } else {
                    if (avatarDrawable != null) {
                        avatarDrawable.setInfo(uidHash != 0 ? uidHash : 1, displayName != null ? displayName : "User", null);
                    }
                    avatarView.setImageDrawable(avatarDrawable);
                }
                avatarView.invalidate();
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateUserInfo();
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        eveDelegate = null;
        listView = null;
        topView = null;
        versionView = null;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    public void setSearchQueryForTest(String query) {
        searchQuery = query != null ? query.trim().toLowerCase(Locale.ROOT) : null;
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(false);
        }
    }

    private void updateActionBarVisible() {
        updateActionBarVisible(false, true);
    }

    private void updateActionBarVisible(boolean force, boolean animated) {
        final boolean visible;
        if (searchItem != null && searchItem.isSearchFieldVisible2()) {
            visible = true;
        } else if (listView != null && listView.getChildCount() > 0) {
            final View firstChild = listView.getChildAt(0);
            visible = (
                listView.getChildAdapterPosition(firstChild) > 0 ||
                firstChild.getY() + firstChild.getHeight() < AndroidUtilities.dp(56)
            );
        } else {
            visible = false;
        }
        if (actionBarVisible == visible && !force) return;
        actionBarVisible = visible;
        if (actionBarVisibleAnimator != null) {
            actionBarVisibleAnimator.cancel();
            actionBarVisibleAnimator = null;
        }
        float currentAlpha = actionBar != null && actionBar.getTitleTextView() != null ? actionBar.getTitleTextView().getAlpha() : (visible ? 0f : 1f);
        if (!animated) {
            setActionBarVisibilityFactor(visible ? 1f : 0f);
        } else {
            actionBarVisibleAnimator = ValueAnimator.ofFloat(currentAlpha, visible ? 1.0f : 0.0f);
            actionBarVisibleAnimator.addUpdateListener(a -> setActionBarVisibilityFactor((float) a.getAnimatedValue()));
            actionBarVisibleAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            actionBarVisibleAnimator.setDuration(420);
            actionBarVisibleAnimator.start();
        }
    }

    private void setActionBarVisibilityFactor(float factor) {
        if (actionBar != null && actionBar.getTitleTextView() != null) {
            actionBar.getTitleTextView().setAlpha(factor);
        }
    }

    private void updateAvatarHide() {
        if (listView == null || avatarContainer == null || topView == null) return;
        if (listView.getChildCount() == 0) return;
        View firstChild = listView.getChildAt(0);
        int pos = listView.getChildAdapterPosition(firstChild);
        float shown;
        if (pos > 0) {
            shown = 0f;
        } else {
            int h = Math.max(1, topView.getHeight());
            shown = MathUtils.clamp(1f + firstChild.getY() / (h * 0.65f), 0f, 1f);
        }
        float scale = AndroidUtilities.lerp(0.65f, 1f, shown);
        avatarContainer.setScaleX(scale);
        avatarContainer.setScaleY(scale);
        avatarContainer.setAlpha(shown);
        if (cameraButton != null) {
            cameraButton.setAlpha(MathUtils.clamp((shown - 0.5f) / 0.5f, 0f, 1f));
        }
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (searchItem != null && searchItem.isSearchFieldVisible2()) {
            if (invoked) {
                actionBar.closeSearchField(true);
            }
            return false;
        }
        return super.onBackPressed(invoked);
    }

    /**
     * Ref 04 (04_settings_ask_question_dialog.jpg): Restored upstream AlertsCreator.createSupportAlert
     * using LinkSpanDrawable.LinksTextView (16dp, 23dp horizontal padding, LinkMovementMethodMy)
     * inside original org.telegram.ui.ActionBar.AlertDialog.Builder.
     */
    public AlertDialog showAskQuestionDialog(Runnable onOpenFaq, Runnable onAskSupport) {
        Context context = getParentActivity();
        if (context == null) return null;

        final AlertDialog[] dialogRef = new AlertDialog[1];
        final LinkSpanDrawable.LinksTextView message = new LinkSpanDrawable.LinksTextView(context, getResourceProvider());

        String intro = "Please note that EVE Support replies are handled by our team. We try to respond as quickly as possible, but it may take a while.\n\n";
        String faqPromptPrefix = "Please take a look at the ";
        String faqLinkText = "EVE FAQ";
        String faqPromptSuffix = ": it has answers to most questions and important tips for ";
        String troubleLinkText = "troubleshooting";
        String ending = ".";

        SpannableStringBuilder spanned = new SpannableStringBuilder();
        spanned.append(intro);
        spanned.append(faqPromptPrefix);
        int faqStart = spanned.length();
        spanned.append(faqLinkText);
        int faqEnd = spanned.length();
        spanned.append(faqPromptSuffix);
        int troubleStart = spanned.length();
        spanned.append(troubleLinkText);
        int troubleEnd = spanned.length();
        spanned.append(ending);

        ClickableSpan faqSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                if (onOpenFaq != null) onOpenFaq.run();
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                ds.setColor(Theme.getColor(Theme.key_dialogTextLink, getResourceProvider()));
                ds.setUnderlineText(false);
            }
        };
        ClickableSpan troubleSpan = new ClickableSpan() {
            @Override
            public void onClick(@NonNull View widget) {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                if (onOpenFaq != null) onOpenFaq.run();
            }

            @Override
            public void updateDrawState(@NonNull TextPaint ds) {
                ds.setColor(Theme.getColor(Theme.key_dialogTextLink, getResourceProvider()));
                ds.setUnderlineText(false);
            }
        };
        spanned.setSpan(faqSpan, faqStart, faqEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        spanned.setSpan(troubleSpan, troubleStart, troubleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        message.setText(spanned);
        message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        message.setLinkTextColor(Theme.getColor(Theme.key_dialogTextLink, getResourceProvider()));
        message.setHighlightColor(Theme.getColor(Theme.key_dialogLinkSelection, getResourceProvider()));
        message.setPadding(AndroidUtilities.dp(23), 0, AndroidUtilities.dp(23), 0);
        message.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        message.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));

        AlertDialog.Builder builder = new AlertDialog.Builder(context, getResourceProvider());
        builder.setView(message);
        builder.setTitle(LocaleController.getString(R.string.AskAQuestion));
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton("Ask Support", (d, which) -> {
            if (onAskSupport != null) onAskSupport.run();
        });
        AlertDialog dialog = builder.create();
        dialogRef[0] = dialog;
        showDialog(dialog);
        return dialog;
    }

    private boolean matchesSearch(CharSequence title, CharSequence subtitle, String... keywords) {
        if (searchQuery == null || searchQuery.isEmpty()) return true;
        if (title != null && title.toString().toLowerCase(Locale.ROOT).contains(searchQuery)) return true;
        if (subtitle != null && subtitle.toString().toLowerCase(Locale.ROOT).contains(searchQuery)) return true;
        if (keywords != null) {
            for (String kw : keywords) {
                if (kw != null && kw.toLowerCase(Locale.ROOT).contains(searchQuery)) return true;
            }
        }
        return false;
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean isSearching = searchQuery != null && !searchQuery.isEmpty();

        if (!isSearching) {
            items.add(UItem.asCustomShadow(topView, 200));
        }

        if (matchesSearch(LocaleController.getString(R.string.SettingsAccount), LocaleController.getString(R.string.SettingsAccountInfo), "profile", "name", "username", "phone", "mobile", "bio", "birthday", "photo")) {
            items.add(SettingCell.Factory.of(ROW_ACCOUNT, IconBackgroundColors.BLUE.top, IconBackgroundColors.BLUE.bottom, R.drawable.settings_account, LocaleController.getString(R.string.SettingsAccount), LocaleController.getString(R.string.SettingsAccountInfo)));
        }
        if (matchesSearch(LocaleController.getString(R.string.SettingsChat), LocaleController.getString(R.string.SettingsChatInfo), "theme", "dark", "night", "day", "appearance", "color", "wallpaper", "font")) {
            items.add(SettingCell.Factory.of(ROW_CHAT, IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.settings_chat, LocaleController.getString(R.string.SettingsChat), LocaleController.getString(R.string.SettingsChatInfo)));
        }
        if (matchesSearch(LocaleController.getString(R.string.SettingsPrivacySecurity), LocaleController.getString(R.string.SettingsPrivacySecurityInfo), "privacy", "security", "passcode", "pin", "lock", "biometric", "fingerprint")) {
            items.add(SettingCell.Factory.of(ROW_PRIVACY, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.settings_privacy, LocaleController.getString(R.string.SettingsPrivacySecurity), LocaleController.getString(R.string.SettingsPrivacySecurityInfo)));
        }
        if (matchesSearch(LocaleController.getString(R.string.SettingsNotifications), LocaleController.getString(R.string.SettingsNotificationsInfo), "notifications", "alerts", "reminders", "sounds", "badges")) {
            items.add(SettingCell.Factory.of(ROW_NOTIFICATIONS, IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom, R.drawable.settings_sounds, LocaleController.getString(R.string.SettingsNotifications), LocaleController.getString(R.string.SettingsNotificationsInfo)));
        }
        if (matchesSearch(LocaleController.getString(R.string.SettingsData), LocaleController.getString(R.string.SettingsDataInfo), "data", "storage", "cache", "clear", "disk", "media")) {
            items.add(SettingCell.Factory.of(ROW_DATA, IconBackgroundColors.BLUE_DEEP.top, IconBackgroundColors.BLUE_DEEP.bottom, R.drawable.settings_data, LocaleController.getString(R.string.SettingsData), LocaleController.getString(R.string.SettingsDataInfo)));
        }
        if (matchesSearch(LocaleController.getString(R.string.SettingsPowerSaving), LocaleController.getString(R.string.SettingsPowerSavingInfo), "power", "battery", "animations", "transitions", "lite", "performance")) {
            items.add(SettingCell.Factory.of(ROW_POWER, IconBackgroundColors.ORANGE_DEEP.top, IconBackgroundColors.ORANGE_DEEP.bottom, R.drawable.settings_power, LocaleController.getString(R.string.SettingsPowerSaving), LocaleController.getString(R.string.SettingsPowerSavingInfo)));
        }
        if (matchesSearch(LocaleController.getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName(), "language", "locale", "hindi", "english", "translate")) {
            // Upstream SettingsActivity.java:807 & Ref 19: current language name is the subtitle
            items.add(SettingCell.Factory.of(ROW_LANGUAGE, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_language, LocaleController.getString(R.string.SettingsLanguage), LocaleController.getCurrentLanguageName()));
        }

        if (!isSearching) {
            items.add(UItem.asShadow(null));
        }

        // EVE Target Exams section card (real EVE exam categories selector)
        if (matchesSearch(LocaleController.getString(R.string.TargetExams), LocaleController.getString(R.string.TargetExamsInfo), "target", "exams", "categories", "ssc", "rpsc", "upsc", "railway")) {
            items.add(SettingCell.Factory.of(ROW_TARGET_EXAMS, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_features, LocaleController.getString(R.string.TargetExams), LocaleController.getString(R.string.TargetExamsInfo)));
        }

        boolean hasHelp = false;
        boolean matchesAsk = matchesSearch(LocaleController.getString(R.string.AskAQuestion), null, "help", "support", "question", "feedback", "contact", "bug");
        boolean matchesFaq = matchesSearch(LocaleController.getString(R.string.TelegramFAQ), null, "faq", "about", "version", "eve");
        boolean matchesFeatures = matchesSearch(LocaleController.getString(R.string.TelegramFeatures), null, "features", "guide", "about");
        boolean matchesPolicy = matchesSearch(LocaleController.getString(R.string.PrivacyPolicy), null, "policy", "terms", "legal", "privacy");
        if (matchesAsk || matchesFaq || matchesFeatures || matchesPolicy) {
            hasHelp = true;
        }

        if (!isSearching || hasHelp) {
            if (!isSearching) items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(LocaleController.getString(R.string.SettingsHelp)));
        }

        if (matchesAsk) {
            items.add(SettingCell.Factory.of(ROW_ASK, IconBackgroundColors.ORANGE.top, IconBackgroundColors.ORANGE.bottom, R.drawable.settings_ask, LocaleController.getString(R.string.AskAQuestion)));
        }
        if (matchesFaq) {
            items.add(SettingCell.Factory.of(ROW_FAQ, IconBackgroundColors.BLUE_LIGHT.top, IconBackgroundColors.BLUE_LIGHT.bottom, R.drawable.settings_faq, "EVE FAQ"));
        }
        if (matchesFeatures) {
            items.add(SettingCell.Factory.of(ROW_FEATURES, IconBackgroundColors.PURPLE.top, IconBackgroundColors.PURPLE.bottom, R.drawable.settings_features, "EVE Features"));
        }
        if (matchesPolicy) {
            items.add(SettingCell.Factory.of(ROW_POLICY, IconBackgroundColors.GREEN.top, IconBackgroundColors.GREEN.bottom, R.drawable.settings_policy, LocaleController.getString(R.string.PrivacyPolicy)));
        }

        if (!isSearching) {
            items.add(UItem.asShadow(null));
            items.add(UItem.asCustomShadow(versionView));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null || item.id <= 0) return;
        if (eveDelegate != null) {
            eveDelegate.onRowClicked(item.id);
        }
    }

    private boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        themeDescriptions.add(new ThemeDescription(titleView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(subtitleView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(versionView, ThemeDescription.FLAG_TEXTCOLOR, null, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{SettingCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{SettingCell.class}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{SettingCell.class}, null, null, null, Theme.key_windowBackgroundWhiteGrayText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{SettingCell.class}, null, null, null, Theme.key_windowBackgroundWhiteBlueText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{org.telegram.ui.Cells.HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{org.telegram.ui.Cells.ShadowSectionCell.class}, null, null, null, Theme.key_windowBackgroundGray));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, () -> {
            if (cameraButton != null) {
                cameraButton.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(32), Theme.getColor(Theme.key_windowBackgroundGray)));
            }
            if (cameraBackground != null) {
                cameraBackground.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(28), Theme.getColor(Theme.key_featuredStickers_addButton)));
            }
        }, Theme.key_windowBackgroundGray));
        return themeDescriptions;
    }

    public static class SettingCell extends LinearLayout implements Theme.Colorable {

        private final Theme.ResourcesProvider resourcesProvider;
        private final Background iconBackground;
        private final FrameLayout iconLayout;
        private final ImageView iconView;
        private final LinearLayout textLayout;
        private final TextView titleView;
        private final TextView subtitleView;
        private final TextView valueView;
        private final boolean mini;
        private boolean hasBackground = true;
        private boolean twoLines;
        private boolean needDivider;

        public SettingCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            this(context, resourcesProvider, false);
        }

        public SettingCell(Context context, Theme.ResourcesProvider resourcesProvider, boolean mini) {
            super(context);

            this.resourcesProvider = resourcesProvider;
            this.mini = mini;
            setOrientation(HORIZONTAL);
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));

            iconLayout = new FrameLayout(context);
            iconLayout.setBackground(iconBackground = new Background());
            iconLayout.setClickable(false);

            iconView = new ImageView(context);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconView.setClickable(false);
            iconLayout.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

            textLayout = new LinearLayout(context);
            textLayout.setOrientation(VERTICAL);
            textLayout.setClickable(false);

            titleView = new TextView(context);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setClickable(false);
            textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));

            subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setSingleLine(true);
            subtitleView.setEllipsize(TextUtils.TruncateAt.END);
            subtitleView.setClickable(false);
            textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            valueView.setSingleLine(true);
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            valueView.setClickable(false);
            setClickable(false);
            setFocusable(false);

            if (LocaleController.isRTL) {
                addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, 20, 0, mini ? 12 : 18, 0));
                addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, mini ? 9 : 18, 0));
            } else {
                addView(iconLayout, LayoutHelper.createLinear(28, 28, Gravity.CENTER_VERTICAL | Gravity.LEFT, mini ? 9 : 18, 0, 0, 0));
                addView(textLayout, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, mini ? 12 : 18, 0, 20, 0));
                addView(valueView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 20, 0));
            }
            updateColors();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            return false;
        }

        public void setDivider(boolean divider) {
            if (needDivider != divider) {
                needDivider = divider;
                setWillNotDraw(!divider);
                invalidate();
            }
        }

        @Override
        protected void onDraw(@NonNull Canvas canvas) {
            super.onDraw(canvas);
            if (needDivider) {
                int left = LocaleController.isRTL ? 0 : AndroidUtilities.dp(mini ? 49 : 64);
                int right = LocaleController.isRTL ? getMeasuredWidth() - AndroidUtilities.dp(mini ? 49 : 64) : getMeasuredWidth();
                canvas.drawLine(left, getMeasuredHeight() - 1, right, getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }

        @Override
        public void updateColors() {
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
            iconBackground.setDrawBorder(hasBackground && (resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark()));
            invalidate();
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
            colors.add(Theme.key_windowBackgroundWhiteBlueText);
            colors.add(Theme.key_windowBackgroundWhite);
        }

        public void set(
            int iconColorTop, int iconColorBottom, int icon,
            CharSequence title,
            CharSequence subtitle,
            CharSequence value
        ) {
            hasBackground = iconColorTop != 0 || iconColorBottom != 0;
            iconLayout.setVisibility(icon != 0 ? View.VISIBLE : View.GONE);
            titleView.setTranslationX(icon == 0 ? AndroidUtilities.dp(2) : 0);
            subtitleView.setTranslationX(icon == 0 ? AndroidUtilities.dp(2) : 0);

            iconBackground.setColor(iconColorTop, iconColorBottom);
            iconView.setImageResource(icon);
            titleView.setText(title);
            subtitleView.setVisibility((twoLines = !TextUtils.isEmpty(subtitle)) ? View.VISIBLE : View.GONE);
            subtitleView.setText(subtitle);
            setValue(value);
            updateColors();
        }

        public void setValue(CharSequence value) {
            valueView.setVisibility(!TextUtils.isEmpty(value) ? View.VISIBLE : View.GONE);
            valueView.setText(value);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(mini ? 44 : twoLines ? 60 : 50), MeasureSpec.EXACTLY)
            );
        }

        public static class Background extends Drawable {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private LinearGradient gradient, strokeGradient;
            private final Matrix matrix = new Matrix();
            private int currentTopColor = Integer.MIN_VALUE;
            private int currentBottomColor = Integer.MIN_VALUE;
            private boolean border;

            public Background() {
                strokePaint.setStyle(Paint.Style.STROKE);
                strokeGradient = new LinearGradient(0, 0, 0, AndroidUtilities.dp(28), new int[] { 0x4dffffff, 0, 0x1affffff }, new float[] { 0, 0.5f, 1 }, Shader.TileMode.CLAMP);
                strokePaint.setShader(strokeGradient);
            }

            public void setColor(int topColor, int bottomColor) {
                if (currentTopColor == topColor && currentBottomColor == bottomColor && gradient != null) {
                    return;
                }
                currentTopColor = topColor;
                currentBottomColor = bottomColor;
                gradient = new LinearGradient(0, 0, 0, AndroidUtilities.dp(28), new int[] { topColor, bottomColor }, new float[] { 0, 1 }, Shader.TileMode.CLAMP);
                paint.setShader(gradient);
                invalidateSelf();
            }

            public void setDrawBorder(boolean drawBorder) {
                if (this.border != drawBorder) {
                    this.border = drawBorder;
                    invalidateSelf();
                }
            }

            @Override
            public void draw(@NonNull Canvas canvas) {
                final float r = AndroidUtilities.dp(10);
                AndroidUtilities.rectTmp.set(getBounds());
                if (gradient != null) {
                    matrix.reset();
                    matrix.postTranslate(AndroidUtilities.rectTmp.left, AndroidUtilities.rectTmp.top);
                    gradient.setLocalMatrix(matrix);
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, paint);
                }

                if (border && strokeGradient != null) {
                    final float sw = AndroidUtilities.dp(1);
                    strokePaint.setStrokeWidth(sw);
                    matrix.reset();
                    matrix.postTranslate(AndroidUtilities.rectTmp.left, AndroidUtilities.rectTmp.top);
                    strokeGradient.setLocalMatrix(matrix);
                    AndroidUtilities.rectTmp.inset(sw / 2.0f, sw / 2.0f);
                    canvas.drawRoundRect(AndroidUtilities.rectTmp, r, r, strokePaint);
                }
            }

            @Override
            public void setAlpha(int alpha) {}
            @Override
            public void setColorFilter(@Nullable ColorFilter colorFilter) {}
            @Override
            public int getOpacity() {
                return PixelFormat.TRANSPARENT;
            }
        }

        public static class Factory extends UItem.UItemFactory<SettingCell> {
            static { setup(new Factory()); }

            @Override
            public SettingCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
                return new SettingCell(context, resourcesProvider);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                int iconColorTop    = (int) item.longValue;
                int iconColorBottom = (int) (item.longValue >>> 32);
                SettingCell cell = (SettingCell) view;
                cell.set(
                    iconColorTop, iconColorBottom, item.iconResId,
                    item.text,
                    item.subtext,
                    item.textValue
                );
                cell.setDivider(divider);
            }

            public static UItem of(int id, int iconColorTop, int iconColorBottom, int icon, CharSequence title) {
                return of(id, iconColorTop, iconColorBottom, icon, title, null, null);
            }
            public static UItem of(int id, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence subtitle) {
                return of(id, iconColorTop, iconColorBottom, icon, title, subtitle, null);
            }
            public static UItem of(int id, int iconColorTop, int iconColorBottom, int icon, CharSequence title, CharSequence subtitle, CharSequence value) {
                final UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.iconResId = icon;
                item.text = title;
                item.subtext = subtitle;
                item.textValue = value;
                item.longValue = ((long) iconColorBottom << 32) | (iconColorTop & 0xFFFFFFFFL);
                return item;
            }
        }
    }
}
