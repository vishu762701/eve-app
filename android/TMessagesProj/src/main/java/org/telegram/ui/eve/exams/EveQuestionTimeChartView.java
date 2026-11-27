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
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.Collections;
import java.util.List;

/**
 * Pure Canvas Telegram-themed bar chart rendering seconds spent per question on
 * EveTestResultFragment (green = correct, red = wrong, gray = skipped).
 */
public class EveQuestionTimeChartView extends View implements Theme.Colorable {

    private final Theme.ResourcesProvider resourcesProvider;
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF cardRect = new RectF();
    private final RectF barRect = new RectF();

    private List<EveExamModels.AttemptAnswerReview> answers = Collections.emptyList();

    public EveQuestionTimeChartView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        titlePaint.setTypeface(AndroidUtilities.bold());
        titlePaint.setTextSize(AndroidUtilities.dp(15));
        subPaint.setTextSize(AndroidUtilities.dp(12));
        labelPaint.setTextSize(AndroidUtilities.dp(10));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        updateColors();
    }

    public void bind(List<EveExamModels.AttemptAnswerReview> items) {
        this.answers = items != null ? items : Collections.emptyList();
        invalidate();
        requestLayout();
    }

    @Override
    public void updateColors() {
        cardPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
        int gray = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        gridPaint.setColor(Theme.multAlpha(gray, 0.2f));
        gridPaint.setStrokeWidth(AndroidUtilities.dp(1));
        titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        subPaint.setColor(gray);
        labelPaint.setColor(gray);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = AndroidUtilities.dp(208);
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

        float contentLeft = cardRect.left + AndroidUtilities.dp(16);
        float contentRight = cardRect.right - AndroidUtilities.dp(16);
        float titleY = cardRect.top + AndroidUtilities.dp(26);
        canvas.drawText("Time Per Question (seconds)", contentLeft, titleY, titlePaint);

        int count = Math.min(answers != null ? answers.size() : 0, 30);
        int maxSec = 10;
        int totalSec = 0;
        for (int i = 0; i < count; i++) {
            EveExamModels.AttemptAnswerReview row = answers.get(i);
            int sec = row != null ? Math.max(0, row.timeTakenSeconds) : 0;
            if (sec > maxSec) {
                maxSec = sec;
            }
            totalSec += sec;
        }
        String avgText = count > 0 ? ("Avg " + Math.round(totalSec / (float) count) + "s / Q") : "No timing data";
        float subY = titleY + AndroidUtilities.dp(17);
        canvas.drawText(avgText, contentLeft, subY, subPaint);

        // Legend dots on right of subtitle row
        int greenColor = Theme.getColor(Theme.key_color_green, resourcesProvider);
        int redColor = Theme.getColor(Theme.key_text_RedBold, resourcesProvider);
        int skipColor = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider), 0.45f);
        drawLegend(canvas, contentRight, subY - AndroidUtilities.dp(4), greenColor, redColor, skipColor);

        float chartTop = subY + AndroidUtilities.dp(16);
        float chartBottom = cardRect.bottom - AndroidUtilities.dp(26);
        float chartHeight = Math.max(AndroidUtilities.dp(40), chartBottom - chartTop);
        canvas.drawLine(contentLeft, chartBottom, contentRight, chartBottom, gridPaint);
        canvas.drawLine(contentLeft, chartTop + chartHeight * 0.5f, contentRight, chartTop + chartHeight * 0.5f, gridPaint);

        if (count == 0) {
            return;
        }

        float chartWidth = Math.max(1f, contentRight - contentLeft);
        float slotWidth = chartWidth / count;
        float barWidth = Math.max(AndroidUtilities.dp(4), Math.min(AndroidUtilities.dp(18), slotWidth * 0.62f));
        float radius = AndroidUtilities.dp(3);

        for (int i = 0; i < count; i++) {
            EveExamModels.AttemptAnswerReview row = answers.get(i);
            int sec = row != null ? Math.max(0, row.timeTakenSeconds) : 0;
            if (row == null || row.isUnattempted()) {
                barPaint.setColor(skipColor);
            } else if (row.isCorrect()) {
                barPaint.setColor(greenColor);
            } else {
                barPaint.setColor(redColor);
            }

            float ratio = sec / (float) maxSec;
            float barH = sec == 0 ? AndroidUtilities.dp(3) : Math.max(AndroidUtilities.dp(6), chartHeight * ratio);
            float cx = contentLeft + slotWidth * i + slotWidth * 0.5f;
            float left = cx - barWidth * 0.5f;
            float right = cx + barWidth * 0.5f;
            float top = chartBottom - barH;
            barRect.set(left, top, right, chartBottom);
            canvas.drawRoundRect(barRect, radius, radius, barPaint);

            if (count <= 12 || i == 0 || i == count - 1 || ((i + 1) % 5 == 0)) {
                int num = row != null && row.number > 0 ? row.number : (i + 1);
                canvas.drawText("Q" + num, cx, chartBottom + AndroidUtilities.dp(14), labelPaint);
            }
        }
    }

    private void drawLegend(Canvas canvas, float right, float cy, int green, int red, int gray) {
        float r = AndroidUtilities.dp(3.5f);
        float x = right;
        String[] labels = new String[] { "Skip", "Wrong", "Right" };
        int[] colors = new int[] { gray, red, green };
        for (int i = 0; i < labels.length; i++) {
            float tw = labelPaint.measureText(labels[i]);
            x -= tw;
            float oldAlign = labelPaint.getTextAlign() == Paint.Align.CENTER ? 0 : 1;
            labelPaint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(labels[i], x, cy + AndroidUtilities.dp(3.5f), labelPaint);
            if (oldAlign == 0) {
                labelPaint.setTextAlign(Paint.Align.CENTER);
            }
            x -= AndroidUtilities.dp(8);
            dotPaint.setColor(colors[i]);
            canvas.drawCircle(x, cy, r, dotPaint);
            x -= AndroidUtilities.dp(14);
        }
    }

    public static final class Factory extends UItem.UItemFactory<EveQuestionTimeChartView> {
        static {
            setup(new Factory());
        }

        @Override
        public EveQuestionTimeChartView createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new EveQuestionTimeChartView(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof EveQuestionTimeChartView && item.object instanceof List) {
                @SuppressWarnings("unchecked")
                List<EveExamModels.AttemptAnswerReview> list = (List<EveExamModels.AttemptAnswerReview>) item.object;
                ((EveQuestionTimeChartView) view).bind(list);
            }
        }

        public static UItem of(int id, List<EveExamModels.AttemptAnswerReview> answers) {
            UItem item = UItem.ofFactory(Factory.class);
            item.id = id;
            item.object = answers;
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }
    }
}
