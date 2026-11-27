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
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.SettingsActivity;
import org.telegram.ui.eve.admin.EveAdminConfig;
import org.telegram.ui.eve.admin.EveAdminModels;
import org.telegram.ui.eve.admin.EveAdminRepository;
import org.telegram.ui.eve.admin.EveAdminStudentDetailFragment;
import org.telegram.ui.eve.admin.EveAdminUiChrome;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;

/**
 * Edge-to-Edge Telegram-native Leaderboard with Top-3 Medal highlights, motivating score framing,
 * silent foreground auto-sync, and Admin/Owner-only student profile inspection.
 */
public class EveLeaderboardFragment extends BaseFragment {

    private static final int ID_RETRY = 100;
    private static final int ID_RANK_SUMMARY = 200;
    private static final int BASE_ENTRY_ID = 1000;

    private final String examIdOrOverall;
    private final String titleText;
    private final boolean adminInspectionMode;
    private UniversalRecyclerView listView;
    private EveExamModels.LeaderboardBundle bundle;
    private boolean loading = true;
    private boolean openingStudent = false;
    private boolean destroyed = false;
    private String errorMessage = "";
    private String currentUid = "";
    private final EveAdminUiChrome.AutoSyncController autoSync = new EveAdminUiChrome.AutoSyncController(this::loadData);

    public EveLeaderboardFragment(String examIdOrOverall, String titleText) {
        this(examIdOrOverall, titleText, false);
    }

    public EveLeaderboardFragment(String examIdOrOverall, String titleText, boolean adminInspectionMode) {
        this.examIdOrOverall = examIdOrOverall != null && !examIdOrOverall.trim().isEmpty()
                ? examIdOrOverall.trim()
                : "overall";
        this.titleText = titleText != null && !titleText.trim().isEmpty()
                ? titleText.trim()
                : LocaleController.getString(R.string.EveLeaderboard);
        this.adminInspectionMode = adminInspectionMode && EveAdminConfig.isAdminAvailable();
        try {
            FirebaseUser u = FirebaseAuth.getInstance().getCurrentUser();
            if (u != null && u.getUid() != null) {
                currentUid = u.getUid();
            }
        } catch (Throwable ignored) {
        }
    }

    public boolean isAdminInspectionMode() {
        return adminInspectionMode;
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

        EveExamModels.LeaderboardBundle cached = EveExamRepository.getInstance().getCachedLeaderboard(examIdOrOverall);
        if (cached != null) {
            bundle = cached;
            loading = false;
        }

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
                        }
                    }
                },
                null
        );
        listView = chrome.listView;
        fragmentView = chrome.rootView;
        loadData();
        return chrome.rootView;
    }

    @Override
    public void onResume() {
        super.onResume();
        autoSync.onResume();
    }

    @Override
    public void onPause() {
        autoSync.onPause();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        destroyed = true;
        autoSync.destroy();
        super.onFragmentDestroy();
    }

    private void loadData() {
        loading = bundle == null;
        errorMessage = "";
        if (loading) {
            updateList();
        }
        EveExamRepository.getInstance().loadLeaderboard(examIdOrOverall, (loaded, err) -> {
            if (destroyed) return;
            boolean wasNull = bundle == null;
            loading = false;
            autoSync.markSynced();
            if (loaded != null) {
                boolean changed = wasNull || !leaderboardEquals(bundle, loaded);
                bundle = loaded;
                errorMessage = "";
                if (changed) {
                    updateList();
                }
            } else {
                errorMessage = err != null ? err : "Unable to load leaderboard.";
                if (wasNull) {
                    updateList();
                }
            }
        });
    }

    private static boolean leaderboardEquals(EveExamModels.LeaderboardBundle a, EveExamModels.LeaderboardBundle b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.entries.size() != b.entries.size()) return false;
        for (int i = 0; i < a.entries.size(); i++) {
            EveExamModels.LeaderboardEntry ea = a.entries.get(i);
            EveExamModels.LeaderboardEntry eb = b.entries.get(i);
            if (!TextUtils.equals(ea.userId, eb.userId)
                    || Double.compare(ea.score, eb.score) != 0
                    || ea.timeTakenSeconds != eb.timeTakenSeconds) {
                return false;
            }
        }
        if (a.stats == null && b.stats == null) return true;
        if (a.stats == null || b.stats == null) return false;
        return a.stats.participants == b.stats.participants
                && Objects.equals(a.stats.myRank, b.stats.myRank)
                && Objects.equals(a.stats.myScore, b.stats.myScore);
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
        if (!errorMessage.isEmpty() && bundle == null) {
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
            items.add(RankSummaryCard.Factory.of(ID_RANK_SUMMARY, bundle.stats, adminInspectionMode));
        }

        if (bundle == null || bundle.entries.isEmpty()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.EveLeaderboard)));
            items.add(UItem.asShadow(LocaleController.getString(R.string.EveNoLeaderboard)));
            return;
        }

        for (int i = 0; i < bundle.entries.size(); i++) {
            EveExamModels.LeaderboardEntry entry = bundle.entries.get(i);
            boolean isSelf = !adminInspectionMode && !currentUid.isEmpty() && TextUtils.equals(currentUid, entry.userId);
            items.add(RankRowCell.Factory.of(BASE_ENTRY_ID + i, i + 1, entry, isSelf));
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_RETRY) {
            loadData();
            return;
        }
        if (!adminInspectionMode || !EveAdminConfig.isAdminAvailable()) {
            return;
        }
        int idx = item.id - BASE_ENTRY_ID;
        if (bundle != null && idx >= 0 && idx < bundle.entries.size()) {
            openAdminStudentProfile(bundle.entries.get(idx));
        }
    }

    private void openAdminStudentProfile(EveExamModels.LeaderboardEntry entry) {
        if (openingStudent || destroyed || entry == null || TextUtils.isEmpty(entry.userId)) {
            return;
        }
        if (!EveAdminConfig.isAdminAvailable()) {
            return;
        }
        openingStudent = true;
        EveAdminRepository repo = EveAdminRepository.getInstance();
        EveAdminRepository.ResultCallback<EveAdminModels.AdminStudentItem> cb =
                new EveAdminRepository.ResultCallback<EveAdminModels.AdminStudentItem>() {
                    @Override
                    public void onSuccess(EveAdminModels.AdminStudentItem student) {
                        openingStudent = false;
                        if (destroyed) return;
                        if (student == null) {
                            BulletinFactory.of(EveLeaderboardFragment.this)
                                    .createErrorBulletin("Student profile not found.").show();
                            return;
                        }
                        presentFragment(new EveAdminStudentDetailFragment(student, EveLeaderboardFragment.this::loadData));
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        openingStudent = false;
                        if (!destroyed) {
                            BulletinFactory.of(EveLeaderboardFragment.this)
                                    .createErrorBulletin(message != null ? message : "Unable to load student profile.").show();
                        }
                    }
                };
        repo.runWithAdminToken(cb, token -> repo.postSuccess(cb, repo.fetchStudentById(token, entry.userId, entry.displayName)));
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
            setTag(RecyclerListView.TAG_NOT_SECTION);
            setPadding(dp(16), dp(10), dp(16), dp(10));

            card = new LinearLayout(context);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(14), dp(12), dp(14), dp(12));
            addView(card, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

            LinearLayout col = new LinearLayout(context);
            col.setOrientation(LinearLayout.VERTICAL);
            rankTitle = new TextView(context);
            rankTitle.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15.5f);
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

        void bind(EveExamModels.LeaderboardStats stats, boolean adminMode) {
            if (adminMode) {
                rankTitle.setText("Leaderboard Overview");
                StringBuilder det = new StringBuilder();
                det.append(stats.participants).append(" ranked student").append(stats.participants == 1 ? "" : "s");
                det.append(" • Topper: ").append(formatScore(stats.topperScore));
                if (stats.averageScore > 0) {
                    det.append(" • Avg: ").append(formatScore(stats.averageScore));
                }
                rankDetail.setText(det.toString());
                rightMetric.setVisibility(VISIBLE);
                rightMetric.setText("Tap row to inspect");
            } else {
                String rankStr = stats.myRank != null
                        ? ("#" + stats.myRank + " of " + stats.participants)
                        : ("Keep practicing! • " + stats.participants + " participants");
                rankTitle.setText(stats.myRank != null
                        ? (LocaleController.getString(R.string.EveYourRank) + ": " + rankStr)
                        : rankStr);
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
            }
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(14));
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
                    ((RankSummaryCard) view).bind((EveExamModels.LeaderboardStats) item.object, item.checked);
                }
            }

            @Override
            public boolean equals(UItem a, UItem b) {
                return a.id == b.id && a.checked == b.checked;
            }

            static UItem of(int id, EveExamModels.LeaderboardStats stats, boolean adminMode) {
                UItem item = UItem.ofFactory(Factory.class);
                item.id = id;
                item.object = stats;
                item.checked = adminMode;
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
            rankBadge.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            rankBadge.setTypeface(AndroidUtilities.bold());

            avatarDrawable = new AvatarDrawable();
            avatarView = new BackupImageView(context);
            avatarView.setRoundRadius(dp(21));

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
            scorePill.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13f);
            scorePill.setTypeface(AndroidUtilities.bold());
            scorePill.setPadding(dp(10), dp(5), dp(10), dp(5));

            LinearLayout row = new LinearLayout(context);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(rankBadge, LayoutHelper.createLinear(36, 28, Gravity.CENTER_VERTICAL, 14, 0, 10, 0));
            row.addView(avatarView, LayoutHelper.createLinear(42, 42, Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
            row.addView(col, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 8, 0));
            row.addView(scorePill, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 0, 14, 0));
            addView(row, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 0, 10, 0, 10));

            updateColors();
        }

        void bind(int rank, EveExamModels.LeaderboardEntry entry, boolean isSelf) {
            this.rank = rank;
            this.isSelf = isSelf;
            if (rank == 1) {
                rankBadge.setText("1st");
            } else if (rank == 2) {
                rankBadge.setText("2nd");
            } else if (rank == 3) {
                rankBadge.setText("3rd");
            } else {
                rankBadge.setText("#" + rank);
            }
            String seed = !TextUtils.isEmpty(entry.userId) ? entry.userId : entry.displayName;
            EveAdminUiChrome.bindAvatar(avatarView, avatarDrawable, seed, entry.displayName, "", 42);
            nameView.setText(isSelf ? (entry.displayName + " (You)") : entry.displayName);

            StringBuilder sub = new StringBuilder();
            if (!TextUtils.isEmpty(entry.examName)) {
                sub.append(entry.examName);
            }
            if (entry.accuracy >= 60) {
                if (sub.length() > 0) sub.append(" • ");
                sub.append(entry.accuracy).append("% accuracy");
            }
            if (entry.timeTakenSeconds > 0) {
                if (sub.length() > 0) sub.append(" • ");
                sub.append(entry.timeTakenSeconds / 60).append("m ").append(entry.timeTakenSeconds % 60).append("s");
            }
            subView.setText(sub.toString());
            subView.setVisibility(sub.length() == 0 ? GONE : VISIBLE);
            scorePill.setText(entry.total > 0 ? (formatScore(entry.score) + " / " + entry.total) : (formatScore(entry.score) + " pts"));
            updateColors();
        }

        @Override
        public void updateColors() {
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int medalColor;
            if (rank == 1) {
                medalColor = 0xFFD99A00;
            } else if (rank == 2) {
                medalColor = 0xFF78909C;
            } else if (rank == 3) {
                medalColor = 0xFFB86B2B;
            } else {
                medalColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
            }

            if (isSelf) {
                setBackgroundColor(Theme.multAlpha(accent, 0.08f));
            } else if (rank <= 3) {
                setBackgroundColor(Theme.multAlpha(medalColor, 0.05f));
            } else {
                setBackgroundColor(0);
            }

            rankBadge.setTextColor(rank <= 3 ? medalColor : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            rankBadge.setBackground(Theme.createRoundRectDrawable(dp(9), Theme.multAlpha(medalColor, rank <= 3 ? 0.18f : 0.08f)));

            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            subView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            int pillColor = rank == 1 ? 0xFFD99A00 : accent;
            scorePill.setTextColor(pillColor);
            scorePill.setBackground(Theme.createRoundRectDrawable(dp(10), Theme.multAlpha(pillColor, 0.12f)));
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
                    MeasureSpec.makeMeasureSpec(dp(64), MeasureSpec.EXACTLY));
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
