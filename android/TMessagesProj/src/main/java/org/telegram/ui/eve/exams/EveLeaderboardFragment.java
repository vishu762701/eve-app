package org.telegram.ui.eve.exams;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminUiChrome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;

/**
 * Edge-to-Edge Telegram-native Leaderboard showing student rank summary card
 * and medal/avatar ranked entries with current user highlight.
 */
public class EveLeaderboardFragment extends BaseFragment {

    private static final int MENU_REFRESH = 1;
    private static final int ID_RETRY = 100;
    private static final int ID_RANK_SUMMARY = 200;
    private static final int BASE_ENTRY_ID = 1000;

    private final String examIdOrOverall;
    private final String titleText;
    private UniversalRecyclerView listView;
    private EveExamModels.LeaderboardBundle bundle;
    private boolean loading = true;
    private boolean destroyed = false;
    private String errorMessage = "";
    private String currentUid = "";

    public EveLeaderboardFragment(String examIdOrOverall, String titleText) {
        this.examIdOrOverall = examIdOrOverall != null && !examIdOrOverall.trim().isEmpty()
                ? examIdOrOverall.trim()
                : "overall";
        this.titleText = titleText != null && !titleText.trim().isEmpty()
                ? titleText.trim()
                : LocaleController.getString(R.string.EveLeaderboard);
        try {
            FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            if (u != null && u.getUid() != null) {
                currentUid = u.getUid();
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected boolean useSimpleActionBar() {
        return true;
    }

    @Override
    protected boolean useFadeDrawableForActionBar() {
        return false;
    }

    @Nullable
    @Override
    protected RecyclerListView getListViewForSimpleGlass() {
        return listView;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    @Override
    public View createView(Context context) {
        destroyed = false;
        setHasOwnBackground(true);

        EveAdminUiChrome.Chrome chrome = EveAdminUiChrome.setup(
                this,
                context,
                simpleActionBar,
                getBaseSimpleGlass(),
                glassEngine,
                getResourceProvider(),
                LocaleController.getString(R.string.EveLeaderboard),
                titleText,
                false,
                this::fillItems,
                this::onClick,
                new ActionBar.ActionBarMenuOnItemClick() {
                    @Override
                    public void onItemClick(int id) {
                        if (id == -1) {
                            finishFragment();
                        } else if (id == MENU_REFRESH) {
                            loadData();
                        }
                    }
                },
                null
        );
        listView = chrome.listView;

        if (simpleActionBar != null) {
            SimpleActionBarMenu menu = simpleActionBar.createMenu();
            menu.addItem(MENU_REFRESH, R.drawable.msg_retry, getResourceProvider());
        }

        fragmentView = chrome.rootView;
        loadData();
        return chrome.rootView;
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        super.onFragmentDestroy();
    }

    private void loadData() {
        loading = true;
        errorMessage = "";
        updateList();
        EveExamRepository.getInstance().loadLeaderboard(examIdOrOverall, (loaded, err) -> {
            if (destroyed) return;
            loading = false;
            bundle = loaded;
            errorMessage = err != null ? err : "";
            updateList();
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        EveAdminUiChrome.addTopSpace(items);

        if (loading && bundle == null) {
            for (int i = 0; i < 6; i++) {
                items.add(UItem.asFlicker(FlickerLoadingView.DIALOG_CELL_TYPE));
            }
            return;
        }
        if (!errorMessage.isEmpty()) {
            items.add(SettingsActivity.SettingCell.Factory.of(
                    ID_RETRY,
                    IconBackgroundColors.RED.top, IconBackgroundColors.RED.bottom,
                    R.drawable.msg_retry,
                    "Retry Loading Leaderboard",
                    errorMessage,
                    "Retry"));
            return;
        }
        if (bundle != null && bundle.stats != null) {
            items.add(RankSummaryCard.Factory.of(ID_RANK_SUMMARY, bundle.stats));
        }

        if (bundle == null || bundle.entries.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.EveLeaderboard)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.EveNoLeaderboard)));
            return;
        }

        for (int i = 0; i < bundle.entries.size(); i++) {
            EveExamModels.LeaderboardEntry entry = bundle.entries.get(i);
            boolean isSelf = !currentUid.isEmpty() && TextUtils.equals(currentUid, entry.userId);
            items.add(RankRowCell.Factory.of(BASE_ENTRY_ID + i, i + 1, entry, isSelf));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            loadData();
        }
    }

    private static String formatScore(double score) {
        if (Math.abs(score - Math.rint(score)) < 0.001) {
            return String.valueOf((long) Math.rint(score));
        }
        return String.format(Locale.US, "%.1f", score);
    }

    private static final class RankSummaryCard extends FrameLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final LinearLayout card;
        private final TextView rankTitle;
        private final TextView rankDetail;
        private final TextView rightMetric;

        RankSummaryCard(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;
            setPadding(dp(16), dp(10), dp(16), dp(10));

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            rankTitle = new TextView(context);
            rankTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            rankTitle.setTypeface(AndroidUtilities.bold());
            col.addView(rankTitle, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            rankDetail = new TextView(context);
            rankDetail.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            col.addView(rankDetail, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));
            card.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f));

            rightMetric = new TextView(context);
            rightMetric.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            rightMetric.setTypeface(AndroidUtilities.bold());
            rightMetric.setPadding(dp(10), dp(5), dp(10), dp(5));
            card.addView(rightMetric, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 8, 0, 0, 0));

            updateColors();
        }

        void bind(EveExamModels.LeaderboardStats stats) {
            String rankStr = stats.myRank != null
                    ? ("#" + stats.myRank + " of " + stats.participants)
                    : ("Unranked • " + stats.participants + " participants");
            rankTitle.setText(LocaleController.getString(R.string.EveYourRank) + ": " + rankStr);
            StringBuilder det = new StringBuilder();
            det.append("Topper: ").append(formatScore(stats.topperScore));
            if (stats.averageScore > 0) {
                det.append(" • Avg: ").append(formatScore(stats.averageScore));
            }
            rankDetail.setText(det.toString());
            if (stats.myPercentile != null) {
                rightMetric.setVisibility(VISIBLE);
                rightMetric.setText(String.format(Locale.US, "%.1f%%ile", stats.myPercentile));
            } else {
                rightMetric.setVisibility(GONE);
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(Theme.multAlpha(accent, 0.09f));
            bg.setStroke(dp(1), Theme.multAlpha(accent, 0.25f));
            card.setBackground(bg);

            rankTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            rankDetail.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            rightMetric.setTextColor(accent);
            rightMetric.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(accent, 0.14f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_featuredStickers_addButton);
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
        }

        static final class Factory extends UItem.UItemFactory<RankSummaryCard> {
            static { setup(new Factory()); }

            @Override
            public RankSummaryCard createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider rp) {
                return new RankSummaryCard(context, rp);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof EveExamModels.LeaderboardStats) {
                    ((RankSummaryCard) view).bind((EveExamModels.LeaderboardStats) item.object);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            static UItem of(int id, EveExamModels.LeaderboardStats stats) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = stats;
                return item;
            }
        }
    }

    private static final class RankRowCell extends FrameLayout implements Theme.Colorable {
        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView rankBadge;
        private final AvatarDrawable avatarDrawable;
        private final BackupImageView avatarView;
        private final TextView nameView;
        private final TextView subView;
        private final TextView scorePill;
        private int rank = 1;
        private boolean isSelf;

        RankRowCell(Context context, Theme.ResourcesProvider rp) {
            super(context);
            this.resourcesProvider = rp;

            rankBadge = new TextView(context);
            rankBadge.setGravity(Gravity.CENTER);
            rankBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            rankBadge.setTypeface(AndroidUtilities.bold());

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(20));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);

            nameView = new TextView(context);
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(nameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            subView = new TextView(context);
            subView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            subView.setSingleLine(true);
            subView.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(subView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

            scorePill = new TextView(context);
            scorePill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            scorePill.setTypeface(AndroidUtilities.bold());
            scorePill.setPadding(dp(9), dp(4), dp(9), dp(4));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(rankBadge, LayoutHelper.createLinear(32, 24, Gravity.CENTER_VERTICAL, 14, 0, 10, 0));
            row.addView(avatarView, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            row.addView(scorePill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 10, 0, 10));

            updateColors();
        }

        void bind(int rank, EveExamModels.LeaderboardEntry entry, boolean isSelf) {
            this.rank = rank;
            this.isSelf = isSelf;
            rankBadge.setText("#" + rank);
            String seed = !TextUtils.isEmpty(entry.userId) ? entry.userId : entry.displayName;
            EveAdminUiChrome.bindAvatar(avatarView, avatarDrawable, seed, entry.displayName, "", 40);
            nameView.setText(isSelf ? (entry.displayName + " (You)") : entry.displayName);

            StringBuilder sub = new StringBuilder();
            if (!TextUtils.isEmpty(entry.examName)) {
                sub.append(entry.examName);
            }
            if (entry.accuracy > 0) {
                if (sub.length() > 0) sub.append(" • ");
                sub.append(entry.accuracy).append("% accuracy");
            }
            if (entry.timeTakenSeconds > 0) {
                if (sub.length() > 0) sub.append(" • ");
                sub.append(entry.timeTakenSeconds / 60).append("m ").append(entry.timeTakenSeconds % 60).append("s");
            }
            subView.setText(sub.toString());
            subView.setVisibility(sub.length() == 0 ? GONE : VISIBLE);
            scorePill.setText(formatScore(entry.score) + "/" + entry.total);
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            setBackgroundColor(isSelf ? Theme.multAlpha(accent, 0.08f) : 0);

            int badgeColor;
            if (rank == 1) {
                badgeColor = 0xFFD99A00;
            } else if (rank == 2) {
                badgeColor = 0xFF78909C;
            } else if (rank == 3) {
                badgeColor = 0xFFB86B2B;
            } else {
                badgeColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            }
            rankBadge.setTextColor(rank <= 3 ? badgeColor : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            rankBadge.setBackground(Theme.createRoundRectDrawable(dp(8), Theme.multAlpha(badgeColor, rank <= 3 ? 0.16f : 0.08f)));

            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            scorePill.setTextColor(accent);
            scorePill.setBackground(Theme.createRoundRectDrawable(dp(9), Theme.multAlpha(accent, 0.12f)));
        }

        @Override
        public void addColorKeys(HashSet<Integer> colors) {
            colors.add(Theme.key_featuredStickers_addButton);
            colors.add(Theme.key_windowBackgroundWhiteBlackText);
            colors.add(Theme.key_windowBackgroundWhiteGrayText);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(62), MeasureSpec.EXACTLY));
        }

        static final class Payload {
            final int rank;
            final EveExamModels.LeaderboardEntry entry;
            final boolean isSelf;

            Payload(int rank, EveExamModels.LeaderboardEntry entry, boolean isSelf) {
                this.rank = rank;
                this.entry = entry;
                this.isSelf = isSelf;
            }
        }

        static final class Factory extends UItem.UItemFactory<RankRowCell> {
            static { setup(new Factory()); }

            @Override
            public RankRowCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider rp) {
                return new RankRowCell(context, rp);
            }

            @Override
            public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
                if (item.object instanceof Payload) {
                    Payload p = (Payload) item.object;
                    ((RankRowCell) view).bind(p.rank, p.entry, p.isSelf);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id;
            }

            @Override
            public boolean contentsEquals(UItem a, UItem b) {
                if (!(a.object instanceof Payload) || !(b.object instanceof Payload)) {
                    return Objects.equals(a.object, b.object);
                }
                Payload pa = (Payload) a.object;
                Payload pb = (Payload) b.object;
                return pa.rank == pb.rank && pa.isSelf == pb.isSelf
                        && TextUtils.equals(pa.entry.userId, pb.entry.userId)
                        && pa.entry.score == pb.entry.score;
            }

            static UItem of(int id, int rank, EveExamModels.LeaderboardEntry entry, boolean isSelf) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = new Payload(rank, entry, isSelf);
                return item;
            }
        }
    }
}
