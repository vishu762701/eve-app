package org.telegram.ui.eve.premium;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.QRCodeBottomSheet;

import java.io.OutputStream;

/**
 * Telegram-native {@link QRCodeBottomSheet} presentation for EVE Premium UPI QR codes
 * (Admin preview & Student checkout) with rounded ErrorCorrectionLevel.H QR code,
 * Share/Save QR actions, Open UPI App, and Verify Payment Status.
 */
public final class EvePremiumQrSheet extends QRCodeBottomSheet {

    private final Runnable onSaveQr;
    private final Runnable onOpenUpi;
    private final Runnable onVerify;

    private EvePremiumQrSheet(
            Context context,
            String title,
            String qrContent,
            String captionText,
            @Nullable Theme.ResourcesProvider resourcesProvider,
            @Nullable Runnable onSaveQr,
            @Nullable Runnable onOpenUpi,
            @Nullable Runnable onVerify) {
        super(context, title, qrContent, captionText, false, resourcesProvider);
        this.onSaveQr = onSaveQr;
        this.onOpenUpi = onOpenUpi;
        this.onVerify = onVerify;
        setCenterImage((android.graphics.drawable.Drawable) null);
    }

    @Override
    public Bitmap createQR(Context context, String key, Bitmap oldBitmap) {
        return EvePremiumRepository.generateQrBitmap(key, 768);
    }

    @Override
    protected void onAddExtraContent(LinearLayout container) {
        Context context = getContext();
        int blue = getThemedColor(Theme.key_featuredStickers_addButton);
        int softBg = Theme.multAlpha(blue, 0.12f);

        if (onVerify != null) {
            TextView verifyBtn = new TextView(context);
            verifyBtn.setGravity(Gravity.CENTER);
            verifyBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            verifyBtn.setTypeface(AndroidUtilities.bold());
            verifyBtn.setText("Verify Payment Status");
            verifyBtn.setTextColor(blue);
            verifyBtn.setBackground(Theme.createRoundRectDrawable(dp(24), softBg));
            verifyBtn.setOnClickListener(v -> {
                dismiss();
                onVerify.run();
            });
            container.addView(verifyBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, Gravity.BOTTOM, 16, 4, 16, 4));
        }

        if (onOpenUpi != null) {
            TextView upiBtn = new TextView(context);
            upiBtn.setGravity(Gravity.CENTER);
            upiBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            upiBtn.setTypeface(AndroidUtilities.bold());
            upiBtn.setText("Open UPI App");
            upiBtn.setTextColor(blue);
            upiBtn.setBackground(Theme.createRoundRectDrawable(dp(24), softBg));
            upiBtn.setOnClickListener(v -> onOpenUpi.run());
            container.addView(upiBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46, Gravity.BOTTOM, 16, 4, 16, 4));
        }

        if (onSaveQr != null) {
            TextView saveBtn = new TextView(context);
            saveBtn.setGravity(Gravity.CENTER);
            saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            saveBtn.setTypeface(AndroidUtilities.bold());
            saveBtn.setText("Save QR Image");
            saveBtn.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
            saveBtn.setOnClickListener(v -> onSaveQr.run());
            container.addView(saveBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.BOTTOM, 16, 2, 16, 12));
        }
    }

    public static void showAdminPreview(
            @NonNull BaseFragment fragment,
            @NonNull String upiUri,
            @NonNull String merchantVpa,
            @NonNull String planName,
            int priceInr) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        String caption = planName + " • ₹" + priceInr + "\nUPI ID: " + merchantVpa;
        EvePremiumQrSheet[] holder = new EvePremiumQrSheet[1];
        EvePremiumQrSheet sheet = new EvePremiumQrSheet(
                activity,
                "UPI QR Preview",
                upiUri,
                caption,
                fragment.getResourceProvider(),
                () -> saveQrToMediaStore(fragment, activity, upiUri, holder[0]),
                null,
                null
        );
        holder[0] = sheet;
        sheet.show();
    }

    public static void showStudentCheckout(
            @NonNull BaseFragment fragment,
            @NonNull EvePremiumRepository.PaymentOrder order,
            @NonNull Runnable onVerified) {
        Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        String qrContent = !order.qrData.trim().isEmpty() ? order.qrData.trim() : order.upiUri.trim();
        String caption = order.planName + " • ₹" + order.amount + "\nOrder: " + order.orderId
                + "\nScan with GPay, PhonePe, Paytm, or BHIM";
        EvePremiumQrSheet[] holder = new EvePremiumQrSheet[1];
        Runnable openUpi = order.upiUri.trim().isEmpty() ? null : () -> {
            try {
                activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(order.upiUri)));
            } catch (Throwable t) {
                BulletinFactory.of(fragment).createErrorBulletin("No UPI app found on device.").show();
            }
        };
        Runnable verify = () -> EvePremiumRepository.getInstance().checkOrderStatus(order.orderId, (st, stErr) -> {
            if (st != null && st.isPremium) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info, "EVE Premium activated!").show();
                onVerified.run();
            } else {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
                        st != null ? "Order status: " + st.status : (stErr != null ? stErr : "Pending server verification.")).show();
            }
        });
        EvePremiumQrSheet sheet = new EvePremiumQrSheet(
                activity,
                LocaleController.getString(R.string.EvePremiumPayUpi),
                qrContent,
                caption,
                fragment.getResourceProvider(),
                () -> saveQrToMediaStore(fragment, activity, qrContent, holder[0]),
                openUpi,
                verify
        );
        holder[0] = sheet;
        sheet.show();
    }

    private static void saveQrToMediaStore(BaseFragment fragment, Context context, String content, @Nullable EvePremiumQrSheet sheet) {
        Bitmap bmp = EvePremiumRepository.generateQrBitmap(content, 1024);
        if (bmp == null) {
            BulletinFactory.of(fragment).createErrorBulletin("Unable to save QR image.").show();
            return;
        }
        try {
            String saved = MediaStore.Images.Media.insertImage(
                    context.getContentResolver(),
                    bmp,
                    "EVE_UPI_QR_" + System.currentTimeMillis(),
                    "EVE Premium UPI QR Code"
            );
            if (sheet != null && sheet.isShowing()) {
                sheet.dismiss();
            }
            if (saved != null) {
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.contact_check, "QR code saved to Gallery.").show();
            } else {
                Uri shareUri = AndroidUtilities.getBitmapShareUri(bmp, "eve_upi_qr.png", Bitmap.CompressFormat.PNG);
                BulletinFactory.of(fragment).createSimpleBulletin(R.raw.info,
                        shareUri != null ? "Use Share QR Code to save or send." : "Unable to save QR image.").show();
            }
        } catch (Throwable t) {
            BulletinFactory.of(fragment).createErrorBulletin("Permission needed or unable to save QR image.").show();
        }
    }
}
