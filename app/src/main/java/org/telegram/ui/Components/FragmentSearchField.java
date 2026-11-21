package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

@SuppressLint("ViewConstructor")
public class FragmentSearchField extends FrameLayout implements FactorAnimator.Target, Theme.Colorable {
    private static final int ANIMATOR_ID_CLOSE_BUTTON_VISIBLE = 0;
    private static final int ANIMATOR_ID_SEARCH_ICON_VISIBLE = 1;

    private final BoolAnimator animatorCloseIconVisible = new BoolAnimator(ANIMATOR_ID_CLOSE_BUTTON_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, false);
    private final BoolAnimator animatorSearchIconVisible = new BoolAnimator(ANIMATOR_ID_SEARCH_ICON_VISIBLE, this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);

    private final Theme.ResourcesProvider resourcesProvider;

    public final ImageView searchIcon;
    public final ImageView closeIcon;
    public final EditTextBoldCursor editText;
    private Drawable bg;
    private Runnable closeButtonClickListener;

    public FragmentSearchField(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        setWillNotDraw(false);

        editText = new EditTextBoldCursor(context) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                setPivotX(getPaddingLeft());
                setPivotY(getMeasuredHeight() / 2.0f);
            }
        };
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        editText.setCursorWidth(1.5f);
        editText.setInputType(editText.getInputType() | InputType.TYPE_TEXT_VARIATION_FILTER);
        editText.setSingleLine(true);
        editText.setBackground(null);
        editText.setVerticalScrollBarEnabled(false);
        editText.setHorizontalScrollBarEnabled(false);
        editText.setPadding(dp(44), 0, dp(44), 0);
        editText.setClipToPadding(true);
        editText.setImeOptions(EditorInfo.IME_ACTION_SEARCH | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editText.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        editText.setHint("Search exams");
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                checkCloseButtonVisible();
            }
        });
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            editText.setLocalePreferredLineHeightForMinimumUsed(false);
        }
        addView(editText, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 0, 0, 0));

        searchIcon = new ImageView(context);
        searchIcon.setScaleType(ImageView.ScaleType.CENTER);
        searchIcon.setImageResource(R.drawable.outline_header_search);
        addView(searchIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 12, 0, 12, 0));

        closeIcon = new ImageView(context);
        closeIcon.setScaleType(ImageView.ScaleType.CENTER);
        closeIcon.setImageResource(R.drawable.ic_close);
        closeIcon.setVisibility(GONE);
        closeIcon.setOnClickListener(v -> {
            editText.setText("");
            if (closeButtonClickListener != null) {
                closeButtonClickListener.run();
            }
        });
        addView(closeIcon, LayoutHelper.createFrame(24, 24, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT), 12, 0, 12, 0));

        updateColors();
    }

    public void setCloseButtonOnClickListener(Runnable listener) {
        this.closeButtonClickListener = listener;
    }

    private void checkCloseButtonVisible() {
        boolean visible = editText.length() > 0;
        animatorCloseIconVisible.setValue(visible, true);
    }

    public void setCloseButtonVisible(boolean visible) {
        animatorCloseIconVisible.setValue(visible, true);
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_CLOSE_BUTTON_VISIBLE) {
            closeIcon.setAlpha(factor);
            closeIcon.setVisibility(factor > 0 ? VISIBLE : GONE);
        } else if (id == ANIMATOR_ID_SEARCH_ICON_VISIBLE) {
            searchIcon.setAlpha(factor);
            searchIcon.setVisibility(factor > 0 ? VISIBLE : GONE);
        }
        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (bg != null) {
            bg.setBounds(
                getPaddingLeft(),
                getPaddingTop(),
                getWidth() - getPaddingRight(),
                getHeight() - getPaddingBottom()
            );
            bg.draw(canvas);
        }
        super.dispatchDraw(canvas);
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    private int getThemedColor(int key, float alpha) {
        int color = getThemedColor(key);
        return Theme.multAlpha(color, alpha);
    }

    @Override
    public void updateColors() {
        final boolean isDark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
        bg = Theme.createRoundRectDrawable(dp(20), getThemedColor(Theme.key_windowBackgroundWhiteBlackText, isDark ? 0.07f : 0.05f));
        searchIcon.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.6f), PorterDuff.Mode.MULTIPLY);
        closeIcon.setColorFilter(getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.6f), PorterDuff.Mode.MULTIPLY);
        closeIcon.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1, dp(17)));
        editText.setHintTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText, 0.5f));
        editText.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setCursorColor(getThemedColor(Theme.key_groupcreate_cursor));
        invalidate();
    }
}
