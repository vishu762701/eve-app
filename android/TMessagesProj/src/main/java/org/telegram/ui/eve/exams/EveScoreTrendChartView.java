/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.eve.exams;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Pure Canvas Telegram-themed score trend chart + per-exam accuracy summary
 * displayed at the top of EveResultHistoryFragment.
 */
public class EveScoreTrendChartView extends View implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint areaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final Path linePath = new Path();
    private final Path areaPath = new Path();

    private final List<EveExamModels.AttemptResult> chronological = new ArrayList<>();
    private final List<String> examSummaryLines = new ArrayList<>();

    public EveScoreTrendChartView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(AndroidUtilities.dp(15));
        subPaint.setTextSize(AndroidUtilities.dp(12));
        labelPaint.setTextSize(AndroidUtilities.dp(10.5f));
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeWidth(AndroidUtilities.dp(2.5f));
        dotStrokePaint.setStyle(Paint.Style.STROKE);
        dotStrokePaint.setStrokeWidth(AndroidUtilities.dp(2));
        updateColors();
    }

    public void bind(List<EveExamModels.AttemptResult> attempts) {
        chronological.clear();
        examSummaryLines.clear();
        if (attempts != null && !attempts.isEmpty()) {
            List<EveExamModels.AttemptResult> valid = new ArrayList<>();
            for (EveExamModels.AttemptResult a : attempts) {
                if (a != null) {
                    valid.add(a);
                }
            }
            Collections.sort(valid, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
            int start = Math.max(0, valid.size() - 12);
            for (int i = start; i < valid.size(); i++) {
                chronological.add(valid.get(i));
            }

            Map<String, int[]> perExamStats = new LinkedHashMap<>();
            for (EveExamModels.AttemptResult a : valid) {
                String name = !TextUtils.isEmpty(a.examName) ? a.examName : "Exam";
                int[] agg = perExamStats.get(name);
                if (agg == null) {
                    agg = new int[3];
                    perExamStats.put(name, agg);
                }
                agg[0] += 1;
                agg[1] += a.getAccuracyPercent();
                agg[2] = Math.max(agg[2], a.getAccuracyPercent());
            }
            for (Map.Entry<String, int[]> entry : perExamStats.entrySet()) {
                if (examSummaryLines.size() >= 3) {
                    break;
                }
                int[] agg = entry.getValue();
                int avgAcc = agg[0] > 0 ? Math.round(agg[1] / (float) agg[0]) : 0;
                String shortName = entry.getKey().length() > 26
                        ? entry.getKey().substring(0, 23) + "..."
                        : entry.getKey();
                examSummaryLines.add(String.format(Locale.US, "%s: %d attempt%s • Avg %d%% acc (Best %d%%)",
                        shortName, agg[0], agg[0] == 1 ? "" : "s", avgAcc, agg[2]));
            }
        }
        invalidate();
        requestLayout();
    }

    @Override
    public void updateColors() {
        int cardBg = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        cardPaint.setColor(cardBg);
        gridPaint.setColor(Theme.multAlpha(gray, 0.2f));
        gridPaint.setStrokeWidth(AndroidUtilities.dp(1));
        linePaint.setColor(accent);
        areaPaint.setColor(Theme.multAlpha(accent, 0.14f));
        dotFillPaint.setColor(cardBg);
        dotStrokePaint.setColor(accent);
        titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        subPaint.setColor(gray);
        labelPaint.setColor(gray);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int extraRows = examSummaryLines.size() * AndroidUtilities.dp(17);
        int height = AndroidUtilities.dp(196) + extraRows;
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }

        float padH = AndroidUtilities.dp(14);
        float padV = AndroidUtilities.dp(8);
        cardRect.set(padH, padV, w - padH, h - padV);
        canvas.drawRoundRect(cardRect, AndroidUtilities.dp(16), AndroidUtilities.dp(16), cardPaint);

        float left = cardRect.left + AndroidUtilities.dp(16);
        float right = cardRect.right - AndroidUtilities.dp(16);
        float titleY = cardRect.top + AndroidUtilities.dp(26);
        canvas.drawText("Score Progression Trend", left, titleY, titlePaint);

        float avgAcc = 0f;
        if (!chronological.isEmpty()) {
            int sum = 0;
            for (EveExamModels.AttemptResult a : chronological) {
                sum += a.getAccuracyPercent();
            }
            avgAcc = sum / (float) chronological.size();
        }
        String subText = chronological.isEmpty()
                ? "Complete a test to track progression"
                : String.format(Locale.US, "Last %d attempt%s • Avg Accuracy %.0f%%",
                        chronological.size(), chronological.size() == 1 ? "" : "s", avgAcc);
        float subY = titleY + AndroidUtilities.dp(17);
        canvas.drawText(subText, left, subY, subPaint);

        float summarySpace = examSummaryLines.size() * AndroidUtilities.dp(17) + (examSummaryLines.isEmpty() ? 0 : AndroidUtilities.dp(6));
        float chartTop = subY + AndroidUtilities.dp(18);
        float chartBottom = cardRect.bottom - AndroidUtilities.dp(28) - summarySpace;
        float chartHeight = Math.max(AndroidUtilities.dp(44), chartBottom - chartTop);
        canvas.drawLine(left, chartBottom, right, chartBottom, gridPaint);
        canvas.drawLine(left, chartTop + chartHeight * 0.5f, right, chartTop + chartHeight * 0.5f, gridPaint);

        if (!chronological.isEmpty()) {
            int count = chronological.size();
            float usableW = Math.max(1f, right - left - AndroidUtilities.dp(16));
            float startX = left + AndroidUtilities.dp(8);
            linePath.reset();
            areaPath.reset();

            float[] xs = new float[count];
            float[] ys = new float[count];
            for (int i = 0; i < count; i++) {
                EveExamModels.AttemptResult item = chronological.get(i);
                double pct = item.total > 0 ? Math.max(0.0, Math.min(100.0, (item.score * 100.0) / item.total)) : item.getAccuracyPercent();
                float ratio = (float) (Math.max(0.0, Math.min(100.0, pct)) / 100.0);
                xs[i] = count == 1 ? (startX + usableW * 0.5f) : (startX + (usableW * i) / (count - 1));
                ys[i] = chartBottom - ratio * (chartHeight - AndroidUtilities.dp(6)) - AndroidUtilities.dp(2);
                if (i == 0) {
                    linePath.moveTo(xs[i], ys[i]);
                    areaPath.moveTo(xs[i], chartBottom);
                    areaPath.lineTo(xs[i], ys[i]);
                } else {
                    linePath.lineTo(xs[i], ys[i]);
                    areaPath.lineTo(xs[i], ys[i]);
                }
            }
            areaPath.lineTo(xs[count - 1], chartBottom);
            areaPath.close();

            if (count > 1) {
                canvas.drawPath(areaPath, areaPaint);
                canvas.drawPath(linePath, linePaint);
            }

            float dotR = AndroidUtilities.dp(4);
            labelPaint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < count; i++) {
                canvas.drawCircle(xs[i], ys[i], dotR, dotFillPaint);
                canvas.drawCircle(xs[i], ys[i], dotR, dotStrokePaint);
                if (count <= 7 || i == 0 || i == count - 1 || (i % 2 == 0)) {
                    canvas.drawText("#" + (i + 1), xs[i], chartBottom + AndroidUtilities.dp(14), labelPaint);
                }
            }
        }

        if (!examSummaryLines.isEmpty()) {
            labelPaint.setTextAlign(Paint.Align.LEFT);
            float y = chartBottom + AndroidUtilities.dp(32);
            for (String line : examSummaryLines) {
                canvas.drawText(line, left, y, subPaint);
                y += AndroidUtilities.dp(17);
            }
        }
    }

    public static final class Factory extends UItem.UItemFactory<EveScoreTrendChartView> {
        static {
            setup(new Factory());
        }

        @Override
        public EveScoreTrendChartView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new EveScoreTrendChartView(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof EveScoreTrendChartView && item.object instanceof List) {
                @SuppressWarnings("unchecked")
                List<EveExamModels.AttemptResult> list = (List<EveExamModels.AttemptResult>) item.object;
                ((EveScoreTrendChartView) view).bind(list);
            }
        }

        public static UItem of(int id, List<EveExamModels.AttemptResult> attempts) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = attempts;
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }
    }
}
