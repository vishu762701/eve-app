package org.telegram.ui.eve.premium;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;

import com.google.firebase.auth.FirebaseUser;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.ui.eve.auth.EveAuthManager;
import org.telegram.ui.eve.auth.EveBackendClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository for EVE-controlled Premium plans, student entitlement status,
 * and backend-authoritative UPI/QR checkout orders.
 */
public final class EvePremiumRepository {

    public static final class PlanDetails {
        public final boolean isEnabled;
        public final String planId;
        public final String planName;
        public final int priceInr;
        public final String currency;
        public final int durationDays;
        public final boolean isLifetime;
        public final String description;
        public final List<String> benefits;
        public final boolean qrEnabled;
        public final boolean upiEnabled;
        public final int sessionExpiryMinutes;

        public PlanDetails(
                boolean isEnabled,
                String planId,
                String planName,
                int priceInr,
                String currency,
                int durationDays,
                boolean isLifetime,
                String description,
                List<String> benefits,
                boolean qrEnabled,
                boolean upiEnabled,
                int sessionExpiryMinutes) {
            this.isEnabled = isEnabled;
            this.planId = planId != null ? planId : "default";
            this.planName = planName != null && !planName.isEmpty() ? planName : "EVE Premium";
            this.priceInr = priceInr;
            this.currency = currency != null ? currency : "INR";
            this.durationDays = durationDays;
            this.isLifetime = isLifetime;
            this.description = description != null ? description : "";
            this.benefits = benefits != null ? benefits : Collections.emptyList();
            this.qrEnabled = qrEnabled;
            this.upiEnabled = upiEnabled;
            this.sessionExpiryMinutes = sessionExpiryMinutes;
        }
    }

    public static final class EntitlementStatus {
        public final boolean isPremium;
        public final String status;
        public final String planName;
        public final long activatedAt;
        public final Long expiresAt;
        public final boolean isLifetime;
        public final String source;

        public EntitlementStatus(
                boolean isPremium,
                String status,
                String planName,
                long activatedAt,
                Long expiresAt,
                boolean isLifetime,
                String source) {
            this.isPremium = isPremium;
            this.status = status != null ? status : "NONE";
            this.planName = planName != null ? planName : "";
            this.activatedAt = activatedAt;
            this.expiresAt = expiresAt;
            this.isLifetime = isLifetime;
            this.source = source != null ? source : "";
        }
    }

    public static final class PremiumSnapshot {
        public final PlanDetails plan;
        public final EntitlementStatus status;

        public PremiumSnapshot(PlanDetails plan, EntitlementStatus status) {
            this.plan = plan;
            this.status = status;
        }
    }

    public static final class PaymentOrder {
        public final String orderId;
        public final String planName;
        public final int amount;
        public final String currency;
        public final String paymentMethod;
        public final long expiresAt;
        public final String upiUri;
        public final String qrData;

        public PaymentOrder(
                String orderId,
                String planName,
                int amount,
                String currency,
                String paymentMethod,
                long expiresAt,
                String upiUri,
                String qrData) {
            this.orderId = orderId;
            this.planName = planName;
            this.amount = amount;
            this.currency = currency;
            this.paymentMethod = paymentMethod;
            this.expiresAt = expiresAt;
            this.upiUri = upiUri;
            this.qrData = qrData;
        }
    }

    public static final class OrderStatusResult {
        public final String orderId;
        public final String status;
        public final boolean isPremium;

        public OrderStatusResult(String orderId, String status, boolean isPremium) {
            this.orderId = orderId;
            this.status = status;
            this.isPremium = isPremium;
        }
    }

    public interface Callback<T> {
        void onResult(T data, String error);
    }

    private static volatile EvePremiumRepository instance;

    private final EveBackendClient client = new EveBackendClient();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EvePremiumRepository() {
    }

    public static EvePremiumRepository getInstance() {
        EvePremiumRepository local = instance;
        if (local == null) {
            synchronized (EvePremiumRepository.class) {
                local = instance;
                if (local == null) {
                    local = new EvePremiumRepository();
                    instance = local;
                }
            }
        }
        return local;
    }

    public void loadSnapshot(Callback<PremiumSnapshot> callback) {
        FirebaseUser user = EveAuthManager.getInstance().getCurrentUser();
        if (user == null) {
            loadPublicPlanOnly(callback);
            return;
        }
        user.getIdToken(false).addOnSuccessListener(result -> {
            String token = result != null ? result.getToken() : null;
            if (token == null || token.trim().isEmpty()) {
                loadPublicPlanOnly(callback);
                return;
            }
            executor.execute(() -> {
                try {
                    PlanDetails plan = parsePlan(client.getJsonRoot("/api/premium/plan", token).optJSONObject("data"));
                    EntitlementStatus status = parseStatus(client.getJsonRoot("/api/premium/status", token).optJSONObject("data"));
                    post(callback, new PremiumSnapshot(plan, status), null);
                } catch (Throwable t) {
                    FileLog.e("EvePremiumRepository: loadSnapshot failed", t);
                    post(callback, null, t.getMessage() != null ? t.getMessage() : "Unable to load EVE Premium details.");
                }
            });
        }).addOnFailureListener(e -> loadPublicPlanOnly(callback));
    }

    private void loadPublicPlanOnly(Callback<PremiumSnapshot> callback) {
        executor.execute(() -> {
            try {
                // Plan info requires Firebase auth token on Worker; return clean unauthenticated prompt if signed out.
                post(callback, new PremiumSnapshot(
                        new PlanDetails(false, "default", "EVE Premium", 99, "INR", 30, false,
                                "Sign in with EVE Cloud Account to view your plan & entitlement.",
                                Collections.singletonList("Unlimited eligible mock test reattempts"),
                                true, true, 10),
                        new EntitlementStatus(false, "SIGNED_OUT", "", 0L, null, false, "")
                ), null);
            } catch (Throwable t) {
                post(callback, null, t.getMessage());
            }
        });
    }

    public void createOrder(String paymentMethod, Callback<PaymentOrder> callback) {
        FirebaseUser user = EveAuthManager.getInstance().getCurrentUser();
        if (user == null || user.isAnonymous()) {
            post(callback, null, "Sign in with your Google EVE Cloud account first.");
            return;
        }
        user.getIdToken(true).addOnSuccessListener(result -> {
            String token = result != null ? result.getToken() : null;
            if (token == null || token.trim().isEmpty()) {
                post(callback, null, "Authentication token unavailable.");
                return;
            }
            executor.execute(() -> {
                try {
                    JSONObject body = new JSONObject();
                    body.put("paymentMethod", paymentMethod);
                    JSONObject root = client.postJsonRoot("/api/premium/orders/create", token, body);
                    JSONObject d = root.optJSONObject("data");
                    if (d == null) {
                        post(callback, null, "Invalid payment order response.");
                        return;
                    }
                    PaymentOrder order = new PaymentOrder(
                            d.optString("orderId", ""),
                            d.optString("planName", "EVE Premium"),
                            d.optInt("amount", 0),
                            d.optString("currency", "INR"),
                            d.optString("paymentMethod", paymentMethod),
                            d.optLong("expiresAt", 0L),
                            d.optString("upiUri", ""),
                            d.optString("qrData", "")
                    );
                    post(callback, order, null);
                } catch (Throwable t) {
                    post(callback, null, t.getMessage() != null ? t.getMessage() : "Unable to create payment order.");
                }
            });
        }).addOnFailureListener(e -> post(callback, null, "Could not verify EVE Cloud session."));
    }

    public void checkOrderStatus(String orderId, Callback<OrderStatusResult> callback) {
        FirebaseUser user = EveAuthManager.getInstance().getCurrentUser();
        if (user == null || user.isAnonymous()) {
            post(callback, null, "Not signed in.");
            return;
        }
        user.getIdToken(false).addOnSuccessListener(result -> {
            String token = result != null ? result.getToken() : null;
            if (token == null || token.trim().isEmpty()) {
                post(callback, null, "Authentication token unavailable.");
                return;
            }
            executor.execute(() -> {
                try {
                    JSONObject root = client.getJsonRoot("/api/premium/orders/" + orderId + "/status", token);
                    JSONObject d = root.optJSONObject("data");
                    if (d == null) {
                        post(callback, null, "Order status unavailable.");
                        return;
                    }
                    post(callback, new OrderStatusResult(
                            d.optString("orderId", orderId),
                            d.optString("status", "CREATED"),
                            d.optBoolean("isPremium", false)
                    ), null);
                } catch (Throwable t) {
                    post(callback, null, t.getMessage() != null ? t.getMessage() : "Unable to check payment status.");
                }
            });
        }).addOnFailureListener(e -> post(callback, null, "Could not verify EVE Cloud session."));
    }

    public static Bitmap generateQrBitmap(String content, int sizePx) {
        if (content == null || content.trim().isEmpty()) {
            return null;
        }
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints);
            Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565);
            for (int x = 0; x < sizePx; x++) {
                for (int y = 0; y < sizePx; y++) {
                    bmp.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bmp;
        } catch (Throwable t) {
            FileLog.e("EvePremiumRepository: QR generation failed", t);
            return null;
        }
    }

    private static PlanDetails parsePlan(JSONObject d) {
        if (d == null) {
            return new PlanDetails(false, "default", "EVE Premium", 99, "INR", 30, false, "", Collections.emptyList(), false, false, 10);
        }
        List<String> benefits = new ArrayList<>();
        JSONArray arr = d.optJSONArray("benefits");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String b = arr.optString(i, "").trim();
                if (!b.isEmpty()) {
                    benefits.add(b);
                }
            }
        }
        return new PlanDetails(
                d.optBoolean("isEnabled", false),
                d.optString("planId", "default"),
                d.optString("planName", "EVE Premium"),
                d.optInt("priceInr", 99),
                d.optString("currency", "INR"),
                d.optInt("durationDays", 30),
                d.optBoolean("isLifetime", false),
                d.optString("description", ""),
                benefits,
                d.optBoolean("qrEnabled", true),
                d.optBoolean("upiEnabled", true),
                d.optInt("sessionExpiryMinutes", 10)
        );
    }

    private static EntitlementStatus parseStatus(JSONObject d) {
        if (d == null) {
            return new EntitlementStatus(false, "NONE", "", 0L, null, false, "");
        }
        Long expiresAt = d.isNull("expiresAt") ? null : d.optLong("expiresAt", 0L);
        return new EntitlementStatus(
                d.optBoolean("isPremium", false),
                d.optString("status", "NONE"),
                d.optString("planName", ""),
                d.optLong("activatedAt", 0L),
                expiresAt,
                d.optBoolean("isLifetime", false),
                d.optString("source", "")
        );
    }

    private <T> void post(Callback<T> callback, T data, String error) {
        if (callback != null) {
            mainHandler.post(() -> callback.onResult(data, error));
        }
    }
}
