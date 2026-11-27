/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.eve.exams;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Pure Android PDF helper for downloading exam syllabus PDFs from Telegram Media Vault
 * and generating multi-page Result & Solutions PDFs via android.graphics.pdf.PdfDocument.
 */
public final class EvePdfHelper {

    private static final int PAGE_WIDTH = 595;
    private static final int PAGE_HEIGHT = 842;
    private static final int MARGIN = 36;

    private EvePdfHelper() {
    }

    public static File getPdfExportDir(Context context) {
        File cacheDir = new File(context.getFilesDir(), "cache");
        File pdfDir = new File(cacheDir, "eve_pdfs");
        if (!pdfDir.exists()) {
            pdfDir.mkdirs();
        }
        return pdfDir;
    }

    public static String sanitizeFileName(String raw, String fallback) {
        String base = !TextUtils.isEmpty(raw) ? raw.trim() : fallback;
        String cleaned = base.replaceAll("[^A-Za-z0-9._-]+", "_");
        if (!cleaned.toLowerCase(Locale.US).endsWith(".pdf")) {
            cleaned += ".pdf";
        }
        return cleaned;
    }

    public static void downloadSyllabusPdf(
            BaseFragment fragment,
            String examId,
            String examName,
            String syllabusUrl,
            String syllabusFileName) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        final AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        try {
            progressDialog.show();
        } catch (Throwable ignored) {
        }

        Utilities.globalQueue.postRunnable(() -> {
            File outputFile = null;
            String error = null;
            try {
                String pathOrUrl = !TextUtils.isEmpty(syllabusUrl)
                        ? syllabusUrl.trim()
                        : ("/api/exams/" + (examId != null ? examId.trim() : "") + "/syllabus");
                String resolvedUrl = EveBackendClient.resolveMediaUrl(pathOrUrl);
                if (TextUtils.isEmpty(resolvedUrl)) {
                    throw new IllegalStateException("Syllabus URL is unavailable.");
                }
                HttpURLConnection conn = (HttpURLConnection) new URL(resolvedUrl).openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(25000);
                conn.setInstanceFollowRedirects(true);
                int status = conn.getResponseCode();
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Server returned HTTP " + status);
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                try (InputStream is = conn.getInputStream()) {
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                }
                byte[] bytes = baos.toByteArray();
                if (bytes.length == 0) {
                    throw new IllegalStateException("Syllabus PDF is empty.");
                }
                String safeName = sanitizeFileName(
                        !TextUtils.isEmpty(syllabusFileName) ? syllabusFileName : (examName + "_syllabus"),
                        "exam_syllabus");
                outputFile = new File(getPdfExportDir(context), safeName);
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    fos.write(bytes);
                }
                saveToPublicDownloadsBestEffort(context, outputFile, safeName);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : "Unable to download syllabus PDF.";
            }

            final File finalFile = outputFile;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progressDialog.dismiss();
                } catch (Throwable ignored) {
                }
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (finalFile == null || !finalFile.exists()) {
                    BulletinFactory.of(fragment).createErrorBulletin(
                            finalError != null ? finalError : "Failed to download syllabus PDF.").show();
                    return;
                }
                BulletinFactory.of(fragment).createSuccessBulletin("Syllabus PDF saved").show();
                showPdfActionsDialog(fragment, finalFile, "Syllabus PDF Ready");
            });
        });
    }

    public static void generateAndExportResultPdf(
            BaseFragment fragment,
            EveExamModels.AttemptResult result,
            String studentName,
            String categoryLabel,
            String cutoffStatusText,
            boolean showHindi) {
        if (fragment == null || fragment.getParentActivity() == null || result == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        final AlertDialog progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        try {
            progressDialog.show();
        } catch (Throwable ignored) {
        }

        Utilities.globalQueue.postRunnable(() -> {
            File outputFile = null;
            String error = null;
            try {
                PdfDocument doc = new PdfDocument();
                int pageNum = 1;
                PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create());
                Canvas canvas = page.getCanvas();

                Paint headerBg = new Paint(Paint.ANTI_ALIAS_FLAG);
                headerBg.setColor(Color.rgb(240, 246, 255));
                Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                dividerPaint.setColor(Color.rgb(218, 224, 232));
                dividerPaint.setStrokeWidth(1f);

                TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                titlePaint.setColor(Color.rgb(18, 32, 54));
                titlePaint.setTextSize(17f);
                titlePaint.setFakeBoldText(true);

                TextPaint subPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                subPaint.setColor(Color.rgb(66, 78, 96));
                subPaint.setTextSize(11f);

                TextPaint boldPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                boldPaint.setColor(Color.rgb(24, 28, 36));
                boldPaint.setTextSize(11.5f);
                boldPaint.setFakeBoldText(true);

                TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                bodyPaint.setColor(Color.rgb(42, 46, 54));
                bodyPaint.setTextSize(10.5f);

                TextPaint greenPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                greenPaint.setColor(Color.rgb(22, 130, 72));
                greenPaint.setTextSize(10.5f);
                greenPaint.setFakeBoldText(true);

                TextPaint redPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
                redPaint.setColor(Color.rgb(198, 48, 48));
                redPaint.setTextSize(10.5f);
                redPaint.setFakeBoldText(true);

                int contentWidth = PAGE_WIDTH - MARGIN * 2;
                float y = MARGIN;

                float headerH = TextUtils.isEmpty(cutoffStatusText) ? 104f : 122f;
                canvas.drawRoundRect(MARGIN, y, PAGE_WIDTH - MARGIN, y + headerH, 10f, 10f, headerBg);
                canvas.drawText(
                        !TextUtils.isEmpty(result.examName) ? result.examName : "Exam Result & Solutions",
                        MARGIN + 14, y + 26, titlePaint);
                String studentLine = "Student: " + (!TextUtils.isEmpty(studentName) ? studentName : "Student")
                        + "  |  Category: " + (!TextUtils.isEmpty(categoryLabel) ? categoryLabel : "General");
                canvas.drawText(studentLine, MARGIN + 14, y + 46, subPaint);
                String statsLine = String.format(Locale.US,
                        "Score: %s / %d   |   Accuracy: %d%%   |   Right: %d   Wrong: %d   Skipped: %d",
                        formatScore(result.score), result.total, result.getAccuracyPercent(),
                        result.correct, result.wrong, result.unattempted);
                canvas.drawText(statsLine, MARGIN + 14, y + 66, boldPaint);
                String timeDateLine = "Time Taken: " + formatDuration(result.timeTakenSeconds)
                        + "   |   Date: " + new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.US)
                        .format(new Date(result.timestampMs > 0 ? result.timestampMs : System.currentTimeMillis()));
                canvas.drawText(timeDateLine, MARGIN + 14, y + 86, subPaint);
                if (!TextUtils.isEmpty(cutoffStatusText)) {
                    canvas.drawText("Cutoff Status: " + cutoffStatusText, MARGIN + 14, y + 106, boldPaint);
                }

                y += headerH + 18;
                List<EveExamModels.AttemptAnswerReview> answers = result.answers;
                for (int i = 0; i < answers.size(); i++) {
                    EveExamModels.AttemptAnswerReview ans = answers.get(i);
                    if (ans == null) {
                        continue;
                    }
                    int qNo = ans.number > 0 ? ans.number : (i + 1);
                    String qText = "Q" + qNo + ". " + ans.getLocalizedQuestion(showHindi);
                    StaticLayout qLayout = buildLayout(qText, boldPaint, contentWidth);

                    String yourAns = ans.isUnattempted()
                            ? "Your Answer: Skipped"
                            : "Your Answer: (" + ans.selected + ") " + ans.getLocalizedSelectedText(showHindi);
                    String corrAns = "Correct Answer: (" + ans.correct + ") " + ans.getLocalizedCorrectText(showHindi);
                    StaticLayout yourLayout = buildLayout(yourAns,
                            ans.isUnattempted() ? subPaint : (ans.isCorrect() ? greenPaint : redPaint), contentWidth);
                    StaticLayout corrLayout = buildLayout(corrAns, greenPaint, contentWidth);

                    String expText = ans.getLocalizedExplanation(showHindi);
                    StaticLayout expLayout = !TextUtils.isEmpty(expText)
                            ? buildLayout("Explanation: " + expText, bodyPaint, contentWidth)
                            : null;

                    float blockH = qLayout.getHeight() + yourLayout.getHeight() + corrLayout.getHeight()
                            + (expLayout != null ? expLayout.getHeight() + 4 : 0) + 22f;

                    if (y + blockH > PAGE_HEIGHT - MARGIN - 20) {
                        drawPageFooter(canvas, subPaint, pageNum);
                        doc.finishPage(page);
                        pageNum++;
                        page = doc.startPage(new PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNum).create());
                        canvas = page.getCanvas();
                        y = MARGIN;
                    }

                    canvas.save();
                    canvas.translate(MARGIN, y);
                    qLayout.draw(canvas);
                    canvas.translate(0, qLayout.getHeight() + 4);
                    yourLayout.draw(canvas);
                    canvas.translate(0, yourLayout.getHeight() + 2);
                    corrLayout.draw(canvas);
                    if (expLayout != null) {
                        canvas.translate(0, corrLayout.getHeight() + 4);
                        expLayout.draw(canvas);
                    }
                    canvas.restore();

                    y += blockH - 8f;
                    canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, dividerPaint);
                    y += 10f;
                }

                drawPageFooter(canvas, subPaint, pageNum);
                doc.finishPage(page);

                String safeName = sanitizeFileName(
                        (!TextUtils.isEmpty(result.examName) ? result.examName : "Result") + "_solutions_" + System.currentTimeMillis(),
                        "eve_result");
                outputFile = new File(getPdfExportDir(context), safeName);
                try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                    doc.writeTo(fos);
                }
                doc.close();
                saveToPublicDownloadsBestEffort(context, outputFile, safeName);
            } catch (Throwable t) {
                error = t.getMessage() != null ? t.getMessage() : "Unable to generate Result PDF.";
            }

            final File finalFile = outputFile;
            final String finalError = error;
            AndroidUtilities.runOnUIThread(() -> {
                try {
                    progressDialog.dismiss();
                } catch (Throwable ignored) {
                }
                if (fragment.getParentActivity() == null) {
                    return;
                }
                if (finalFile == null || !finalFile.exists()) {
                    BulletinFactory.of(fragment).createErrorBulletin(
                            finalError != null ? finalError : "Failed to generate Result PDF.").show();
                    return;
                }
                BulletinFactory.of(fragment).createSuccessBulletin("Result & Solutions PDF saved").show();
                showPdfActionsDialog(fragment, finalFile, "Result & Solutions PDF Ready");
            });
        });
    }

    private static void drawPageFooter(Canvas canvas, TextPaint paint, int pageNum) {
        canvas.drawText("EVE Exam Prep • Page " + pageNum, MARGIN, PAGE_HEIGHT - 16, paint);
    }

    @SuppressWarnings("deprecation")
    private static StaticLayout buildLayout(String text, TextPaint paint, int width) {
        String safe = text != null ? text : "";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return StaticLayout.Builder.obtain(safe, 0, safe.length(), paint, Math.max(40, width))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(2f, 1.05f)
                    .build();
        }
        return new StaticLayout(safe, paint, Math.max(40, width), Layout.Alignment.ALIGN_NORMAL, 1.05f, 2f, false);
    }

    private static void saveToPublicDownloadsBestEffort(Context context, File sourceFile, String fileName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || sourceFile == null || !sourceFile.exists()) {
            return;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/EVE");
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri != null) {
                try (OutputStream out = resolver.openOutputStream(uri);
                     FileInputStream in = new FileInputStream(sourceFile)) {
                    if (out != null) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void showPdfActionsDialog(BaseFragment fragment, File file, String title) {
        Context context = fragment.getParentActivity();
        if (context == null || file == null || !file.exists()) {
            return;
        }
        Uri uri;
        try {
            uri = FileProvider.getUriForFile(context, ApplicationLoader.getApplicationId() + ".provider", file);
        } catch (Throwable t) {
            BulletinFactory.of(fragment).createErrorBulletin("Saved to app storage: " + file.getName()).show();
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, fragment.getResourceProvider());
        builder.setTitle(title);
        builder.setMessage("Saved as " + file.getName() + ". Open in PDF viewer or share now?");
        builder.setPositiveButton("Open PDF", (d, w) -> {
            try {
                Intent viewIntent = new Intent(Intent.ACTION_VIEW);
                viewIntent.setDataAndType(uri, "application/pdf");
                viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(viewIntent);
            } catch (Throwable e) {
                BulletinFactory.of(fragment).createErrorBulletin("No PDF viewer installed. Try Share instead.").show();
            }
        });
        builder.setNeutralButton("Share", (d, w) -> {
            try {
                Intent shareIntent = new Intent(Intent.ACTION_SEND);
                shareIntent.setType("application/pdf");
                shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(Intent.createChooser(shareIntent, title));
            } catch (Throwable ignored) {
            }
        });
        builder.setNegativeButton("Close", null);
        fragment.showDialog(builder.create());
    }

    private static String formatScore(double score) {
        if (Math.abs(score - Math.rint(score)) < 0.005) {
            return String.format(Locale.US, "%d", (long) Math.rint(score));
        }
        return String.format(Locale.US, "%.2f", score);
    }

    private static String formatDuration(int totalSeconds) {
        int sec = Math.max(0, totalSeconds);
        int mins = sec / 60;
        int rem = sec % 60;
        return String.format(Locale.US, "%dm %02ds", mins, rem);
    }
}
