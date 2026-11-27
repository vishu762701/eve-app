/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.eve.admin;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Native Canvas Telegram-themed vertical bar chart displaying attempts and
 * average score across exams in EveAdminExamAnalyticsFragment.
 */
public class EveAdminAttemptsBarChartView extends View implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final RectF barRect = new RectF();

    private final List<EveAdminModels.ExamAnalyticsItem> topItems = new ArrayList<>();
    private String rangeLabel = "All Time";

    public EveAdminAttemptsBarChartView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(AndroidUtilities.dp(15));
        subPaint.setTextSize(AndroidUtilities.dp(12));
        valuePaint.setTypeface(AndroidUtilities.bold());
        valuePaint.setTextSize(AndroidUtilities.dp(10.5f));
        valuePaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(AndroidUtilities.dp(10.5f));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        updateColors();
    }

    public void bind(List<EveAdminModels.ExamAnalyticsItem> items, String rangeLabel) {
        this.rangeLabel = !TextUtils.isEmpty(rangeLabel) ? rangeLabel : "All Time";
        topItems.clear();
        if (items != null && !items.isEmpty()) {
            List<EveAdminModels.ExamAnalyticsItem> sorted = new ArrayList<>(items);
            Collections.sort(sorted, (a, b) -> Integer.compare(b.attemptCount, a.attemptCount));
            int limit = Math.min(8, sorted.size());
            for (int i = 0; i < limit; i++) {
                topItems.add(sorted.get(i));
            }
        }
        invalidate();
        requestLayout();
    }

    @Override
    public void updateColors() {
        cardPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        gridPaint.setColor(Theme.multAlpha(gray, 0.2f));
        gridPaint.setStrokeWidth(AndroidUtilities.dp(1));
        barPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        valuePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        subPaint.setColor(gray);
        labelPaint.setColor(gray);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = AndroidUtilities.dp(220);
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
        canvas.drawText("Exam Attempts Overview", left, titleY, titlePaint);

        int totalAttempts = 0;
        int maxAttempts = 1;
        for (EveAdminModels.ExamAnalyticsItem item : topItems) {
            totalAttempts += Math.max(0, item.attemptCount);
            if (item.attemptCount > maxAttempts) {
                maxAttempts = item.attemptCount;
            }
        }
        float subY = titleY + AndroidUtilities.dp(17);
        String subtitle = String.format(Locale.US, "%s • Top %d exams (%d attempts)",
                rangeLabel, topItems.size(), totalAttempts);
        canvas.drawText(subtitle, left, subY, subPaint);

        float chartTop = subY + AndroidUtilities.dp(22);
        float chartBottom = cardRect.bottom - AndroidUtilities.dp(28);
        float chartHeight = Math.max(AndroidUtilities.dp(44), chartBottom - chartTop);
        canvas.drawLine(left, chartBottom, right, chartBottom, gridPaint);
        canvas.drawLine(left, chartTop + chartHeight * 0.5f, right, chartTop + chartHeight * 0.5f, gridPaint);

        if (topItems.isEmpty()) {
            return;
        }

        int count = topItems.size();
        float slotWidth = Math.max(1f, (right - left) / count);
        float barWidth = Math.max(AndroidUtilities.dp(12), Math.min(AndroidUtilities.dp(34), slotWidth * 0.55f));
        float radius = AndroidUtilities.dp(5);

        int primaryBarColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider);
        int secondaryBarColor = Theme.getColor(Theme.key_color_green, resourcesProvider);

        for (int i = 0; i < count; i++) {
            EveAdminModels.ExamAnalyticsItem item = topItems.get(i);
            int val = Math.max(0, item.attemptCount);
            float ratio = val / (float) maxAttempts;
            float barH = val == 0 ? AndroidUtilities.dp(4) : Math.max(AndroidUtilities.dp(8), chartHeight * ratio);

            barPaint.setColor(i == 0 ? secondaryBarColor : primaryBarColor);
            float cx = left + slotWidth * i + slotWidth * 0.5f;
            float barLeft = cx - barWidth * 0.5f;
            float barRight = cx + barWidth * 0.5f;
            float barTop = chartBottom - barH;
            barRect.set(barLeft, barTop, barRight, chartBottom);
            canvas.drawRoundRect(barRect, radius, radius, barPaint);

            canvas.drawText(String.valueOf(val), cx, barTop - AndroidUtilities.dp(5), valuePaint);

            String label = !TextUtils.isEmpty(item.examName) ? item.examName.trim() : "Exam";
            int maxChars = count <= 4 ? 10 : 7;
            if (label.length() > maxChars) {
                label = label.substring(0, maxChars - 1) + "…";
            }
            canvas.drawText(label, cx, chartBottom + AndroidUtilities.dp(15), labelPaint);
        }
    }

    public static final class ChartPayload {
        public final List<EveAdminModels.ExamAnalyticsItem> items;
        public final String rangeLabel;

        public ChartPayload(List<EveAdminModels.ExamAnalyticsItem> items, String rangeLabel) {
            this.items = items;
            this.rangeLabel = rangeLabel;
        }
    }

    public static final class Factory extends UItem.UItemFactory<EveAdminAttemptsBarChartView> {
        static {
            setup(new Factory());
        }

        @Override
        public EveAdminAttemptsBarChartView createView(Context context, UniversalRecyclerView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new EveAdminAttemptsBarChartView(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof EveAdminAttemptsBarChartView && item.object instanceof ChartPayload) {
                ChartPayload payload = (ChartPayload) item.object;
                ((EveAdminAttemptsBarChartView) view).bind(payload.items, payload.rangeLabel);
            }
        }

        public static UItem of(int id, List<EveAdminModels.ExamAnalyticsItem> items, String rangeLabel) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = new ChartPayload(items, rangeLabel);
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }
    }
}
