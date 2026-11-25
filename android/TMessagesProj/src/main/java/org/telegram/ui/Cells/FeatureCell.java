package org.telegram.ui.Cells;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.BottomSheetWithRecyclerListView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.Premium.GLIcon.GLIconRenderer;
import org.telegram.ui.Components.Premium.GLIcon.GLIconTextureView;
import org.telegram.ui.Components.Premium.GLIcon.Icon3D;
import org.telegram.ui.Components.Premium.StarParticlesView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import java.util.ArrayList;
import static org.telegram.messenger.AndroidUtilities.dp;


public class FeatureCell extends LinearLayout {

    public static final int STYLE_SHEET = 1;

    public final ImageView imageView;
    public final LinearLayout textLayout;
    public final TextView titleView;
    public final LinkSpanDrawable.LinksTextView subtitleView;

    public FeatureCell(Context context, int iconResId, CharSequence title, CharSequence text, Theme.ResourcesProvider resourcesProvider) {
        this(context, STYLE_SHEET, resourcesProvider);
        set(iconResId, title, text);
    }

    public FeatureCell(Context context, int style, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        setOrientation(HORIZONTAL);

        setPadding(dp(style == STYLE_SHEET ? 11 : 32), 0, dp(style == STYLE_SHEET ? 11 : 32), dp(style == STYLE_SHEET ? 8 : 12));

        imageView = new ImageView(context);
        ThemeBind.colorFilter(imageView, Theme.key_windowBackgroundWhiteBlackText, PorterDuff.Mode.SRC_IN);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        addView(imageView, LayoutHelper.createLinear(24, 24, Gravity.TOP | Gravity.LEFT, 0, 6, 16, 0));

        textLayout = new LinearLayout(context);
        textLayout.setOrientation(VERTICAL);

        titleView = new LinkSpanDrawable.LinksTextView(context);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        ThemeBind.textColor(titleView, Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
        ThemeBind.linkTextColor(titleView, Theme.key_chat_messageLinkIn, resourcesProvider);
        textLayout.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL, 0, 0, 0, 3));

        subtitleView = new LinkSpanDrawable.LinksTextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        ThemeBind.textColor(subtitleView, Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
        ThemeBind.linkTextColor(subtitleView, Theme.key_chat_messageLinkIn, resourcesProvider);
        textLayout.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL));

        addView(textLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 1f, Gravity.TOP | Gravity.FILL_HORIZONTAL, 0, 0, 0, 0));
    }

    public void set(int iconResId, CharSequence title, CharSequence text) {
        imageView.setImageResource(iconResId);
        titleView.setText(title);
        subtitleView.setText(text);
    }

    public void setTitle(CharSequence text) {
        titleView.setText(text);
    }

    public void setSubtitle(CharSequence text) {
        subtitleView.setText(text);
    }

    public static class Factory extends UItem.UItemFactory<FeatureCell> {
        static { setup(new Factory()); }

        @Override
        public FeatureCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new FeatureCell(context, 0, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((FeatureCell) view).set(
                item.intValue, item.text, item.subtext
            );
        }

        public static UItem of(int iconResId, CharSequence title, CharSequence text) {
            UItem item = UItem.ofFactory(Factory.class);
            item.selectable = false;
            item.intValue = iconResId;
            item.text = title;
            item.subtext = text;
            return item;
        }

    }
}
