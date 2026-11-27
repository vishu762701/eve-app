package org.telegram.ui.eve.auth;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.exams.EveExamRepository;

/**
 * Full-screen Telegram-native Maintenance Mode screen for students.
 * Non-dismissible while maintenance_mode is true and caller is not an Admin/Owner.
 * Automatically dismisses as soon as server /api/app-config returns maintenanceMode=false.
 */
public class EveMaintenanceFragment extends BaseFragment {

    private String maintenanceMessage;
    private Runnable onResolved;
    private boolean checking = false;
    private boolean destroyed = false;

    private TextView titleView;
    private TextView messageView;
    private TextView checkButton;
    private RadialProgressView progressView;
    private ImageView iconView;
    private FrameLayout iconBadge;

    public EveMaintenanceFragment(String maintenanceMessage, Runnable onResolved) {
        this.maintenanceMessage = maintenanceMessage != null && !maintenanceMessage.trim().isEmpty()
                ? maintenanceMessage.trim()
                : "Eve Mock Test is currently undergoing scheduled maintenance. Please check back shortly.";
        this.onResolved = onResolved;
    }

    public void updateMessage(String message) {
        if (message != null && !message.trim().isEmpty()) {
            this.maintenanceMessage = message.trim();
            if (messageView != null) {
                messageView.setText(this.maintenanceMessage);
            }
        }
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        // Non-dismissible while maintenance is active for non-admin users.
        return false;
    }

    @Override
    public boolean isSwipeBackEnabled(android.view.MotionEvent event) {
        return false;
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        if (actionBar != null) {
            actionBar.setAddToContainer(false);
            actionBar.setCastShadows(false);
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    // Intentionally locked during maintenance mode.
                }
            });
        }

        Theme.ResourcesProvider rp = getResourceProvider();
        FrameLayout root = new FrameLayout(context) {
            @Override
            public void setBackgroundColor(int color) {
                super.setBackgroundColor(color);
            }
        };
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite, rp));

        LinearLayout center = new LinearLayout(context);
        center.setOrientation(LinearLayout.VERTICAL);
        center.setGravity(Gravity.CENTER_HORIZONTAL);
        center.setPadding(dp(28), dp(24), dp(28), dp(24));

        iconBadge = new FrameLayout(context);
        iconView = new ImageView(context);
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setImageResource(R.drawable.msg_settings);
        iconBadge.addView(iconView, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        center.addView(iconBadge, LayoutHelper.createLinear(92, 92, Gravity.CENTER_HORIZONTAL, 0, 0, 0, 22));

        titleView = new TextView(context);
        titleView.setText("Scheduled Maintenance");
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        center.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        messageView = new TextView(context);
        messageView.setText(maintenanceMessage);
        messageView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        messageView.setLineSpacing(dp(3), 1f);
        messageView.setGravity(Gravity.CENTER);
        center.addView(messageView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 28));

        FrameLayout btnWrapper = new FrameLayout(context);
        checkButton = new TextView(context);
        checkButton.setText("Check Again");
        checkButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        checkButton.setTypeface(AndroidUtilities.bold());
        checkButton.setGravity(Gravity.CENTER);
        checkButton.setPadding(dp(24), 0, dp(24), 0);
        checkButton.setOnClickListener(v -> recheckMaintenance());
        btnWrapper.addView(checkButton, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));

        progressView = new RadialProgressView(context, rp);
        progressView.setSize(dp(20));
        progressView.setProgressColor(Color.WHITE);
        progressView.setVisibility(View.GONE);
        btnWrapper.addView(progressView, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 0, 0, 16, 0));

        center.addView(btnWrapper, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

        root.addView(center, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));
        applyColors(rp);

        fragmentView = root;
        return root;
    }

    private void applyColors(Theme.ResourcesProvider rp) {
        int bg = Theme.getColor(Theme.key_windowBackgroundWhite, rp);
        int black = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, rp);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, rp);
        int blue = Theme.getColor(Theme.key_featuredStickers_addButton, rp);
        int blueText = Theme.getColor(Theme.key_featuredStickers_buttonText, rp);

        if (fragmentView != null) {
            fragmentView.setBackgroundColor(bg);
        }
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Theme.multAlpha(blue, 0.14f));
        iconBadge.setBackground(circle);
        iconView.setColorFilter(new PorterDuffColorFilter(blue, PorterDuff.Mode.SRC_IN));

        titleView.setTextColor(black);
        messageView.setTextColor(gray);
        checkButton.setTextColor(blueText);
        checkButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(
                dp(14), blue, Theme.getColor(Theme.key_featuredStickers_addButtonPressed, rp)));
        progressView.setProgressColor(blueText);
    }

    public void recheckMaintenance() {
        if (checking || destroyed) return;
        checking = true;
        if (checkButton != null) {
            checkButton.setText("Checking...");
            checkButton.setEnabled(false);
        }
        if (progressView != null) {
            progressView.setVisibility(View.VISIBLE);
        }
        EveExamRepository.getInstance().fetchAppConfig((cfg, err) -> {
            if (destroyed) return;
            checking = false;
            if (checkButton != null) {
                checkButton.setText("Check Again");
                checkButton.setEnabled(true);
            }
            if (progressView != null) {
                progressView.setVisibility(View.GONE);
            }
            if (cfg == null) {
                BulletinFactory.of(this).createErrorBulletin(
                        !TextUtils.isEmpty(err) ? err : "Could not reach server. Try again.").show();
                return;
            }
            if (!cfg.maintenanceMode || EveAdminConfig.isAdminAvailable()) {
                if (onResolved != null) {
                    onResolved.run();
                }
                finishFragment();
            } else {
                updateMessage(cfg.maintenanceMessage);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.info, "Maintenance is still ongoing.").show();
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }
}
