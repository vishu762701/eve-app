package org.telegram.ui.eve.media;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.eve.admin.EveAdminConfig;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Admin Emoji & Sticker Pack Manager backed by Cloudflare Worker + R2/Storage + D1.
 * Supports creating packs, rights-gated chunked Telegram URL/short-name import, Noto preset import,
 * custom asset upload (.json/.tgs/.webm/.webp/.png), preview before publish, publish/unpublish,
 * free/EVE Premium gating, and deletion.
 */
public class EveAdminPackManagerFragment extends BaseFragment {

    private static final int MENU_CREATE = 2;

    private static final int ID_IMPORT_TELEGRAM = 101;
    private static final int ID_IMPORT_NOTO = 102;
    private static final int ID_CREATE_PACK = 103;
    private static final int ID_TOGGLE_GLOBAL_TG = 104;
    private static final int ID_PACK_BASE = 1000;

    private static final int REQ_UPLOAD_ASSET = 9241;

    private UniversalRecyclerView listView;
    private final EvePackRepository repo = EvePackRepository.getInstance();
    private EvePackModels.AdminPackCatalog catalog;
    private boolean loading = true;
    private String errorMessage = "";

    private String pendingUploadPackId = "";
    private String pendingUploadAltEmoji = "✨";

    @Override
    public boolean onFragmentCreate() {
        if (!EveAdminConfig.isAdminAvailable()) {
            return false;
        }
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EveAdminPackManager));
        actionBar.setSubtitle("Worker + R2/Storage + D1");
        actionBar.createMenu().addItem(MENU_CREATE, R.drawable.msg_add);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_CREATE) {
                    showCreatePackDialog();
                }
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        listView = new UniversalRecyclerView(this, this::fillItems, this::onClick, null);
        root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        fragmentView = root;

        refreshCatalog();
        return root;
    }

    private void refreshCatalog() {
        loading = true;
        errorMessage = "";
        updateList();
        repo.loadAdminPacksAsync(new EvePackRepository.Callback<EvePackModels.AdminPackCatalog>() {
            @Override
            public void onSuccess(EvePackModels.AdminPackCatalog data) {
                loading = false;
                catalog = data;
                if (actionBar != null && data != null) {
                    actionBar.setSubtitle((data.r2Configured ? "R2 Storage" : "Fallback Storage")
                            + " • Bot " + (data.telegramBotConfigured ? "Ready" : "Not Set"));
                }
                updateList();
            }

            @Override
            public void onError(String message, int httpStatus, String serverCode) {
                loading = false;
                errorMessage = message != null ? message : "Unable to load admin pack catalog.";
                updateList();
            }
        });
    }

    private void updateList() {
        if (listView != null && listView.adapter != null) {
            listView.adapter.update(true);
        }
    }

    private void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader("Pack Import & Creation Tools"));
        items.add(UItem.asButton(ID_CREATE_PACK, "Create New Emoji / Sticker Pack", "Create free or EVE Premium pack metadata"));
        items.add(UItem.asButton(ID_IMPORT_TELEGRAM, "Import from Telegram URL / Short Name",
                catalog != null && !catalog.telegramBotConfigured
                        ? "Requires TELEGRAM_BOT_TOKEN secret on Worker"
                        : "Chunked Bot API import with rights verification"));
        items.add(UItem.asButton(ID_IMPORT_NOTO, "Import Google Noto Animated Emoji", "Import Popular or Study Noto presets"));

        if (catalog != null) {
            items.add(UItem.asButton(ID_TOGGLE_GLOBAL_TG,
                    "Global Telegram Pack Visibility: " + (catalog.globalTelegramEnabled ? "ENABLED" : "DISABLED"),
                    "Tap to toggle Telegram-sourced packs globally"));
        }

        if (loading) {
            items.add(UItem.asShadow("Loading pack catalog from EVE Cloud..."));
        } else if (!errorMessage.isEmpty()) {
            items.add(UItem.asShadow("Error: " + errorMessage));
        }

        if (catalog != null) {
            items.add(UItem.asHeader("Managed Packs (" + catalog.packs.size() + ")"));
            if (catalog.packs.isEmpty()) {
                items.add(UItem.asShadow("No packs found yet. Create or import a pack above."));
            } else {
                for (int i = 0; i < catalog.packs.size(); i++) {
                    EvePackModels.AdminPackSummary pack = catalog.packs.get(i);
                    String subtitle = String.format(Locale.US,
                            "%s • %s • %d items • v%d • %s • Rights: %s%s",
                            pack.packType.toUpperCase(Locale.US),
                            pack.publicationStatus.toUpperCase(Locale.US),
                            pack.itemCount,
                            pack.version,
                            pack.accessTier.toUpperCase(Locale.US),
                            pack.rightsStatus,
                            pack.enabled ? "" : " • DISABLED");
                    items.add(UItem.asButton(ID_PACK_BASE + i,
                            (pack.featured ? "★ " : "") + pack.title,
                            subtitle));
                }
            }
        }
    }

    private void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == ID_CREATE_PACK) {
            showCreatePackDialog();
        } else if (item.id == ID_IMPORT_TELEGRAM) {
            showTelegramImportDialog();
        } else if (item.id == ID_IMPORT_NOTO) {
            showNotoImportDialog();
        } else if (item.id == ID_TOGGLE_GLOBAL_TG && catalog != null) {
            boolean next = !catalog.globalTelegramEnabled;
            repo.toggleGlobalTelegramStatusAsync(next, new EvePackRepository.Callback<Boolean>() {
                @Override
                public void onSuccess(Boolean data) {
                    BulletinFactory.of(EveAdminPackManagerFragment.this)
                            .createSimpleBulletin(R.raw.info, "Global Telegram packs " + (next ? "enabled." : "disabled.")).show();
                    refreshCatalog();
                }

                @Override
                public void onError(String message, int httpStatus, String serverCode) {
                    BulletinFactory.of(EveAdminPackManagerFragment.this).createErrorBulletin(message).show();
                }
            });
        } else if (item.id >= ID_PACK_BASE && catalog != null) {
            int idx = item.id - ID_PACK_BASE;
            if (idx >= 0 && idx < catalog.packs.size()) {
                showPackActionsDialog(catalog.packs.get(idx));
            }
        }
    }

    private void showCreatePackDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(4));

        EditText titleInput = makeInput(activity, "Pack Title (e.g., Study Rewards)");
        EditText slugInput = makeInput(activity, "Slug ID (lowercase, e.g., study_rewards)");
        EditText categoryInput = makeInput(activity, "Category (e.g., Study, Reactions)");
        categoryInput.setText("General");
        EditText descInput = makeInput(activity, "Short Description");

        Spinner typeSpinner = new Spinner(activity);
        typeSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] { "sticker", "emoji" }));
        Spinner tierSpinner = new Spinner(activity);
        tierSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] { "free", "premium" }));
        TextCheckCell featuredCheck = new TextCheckCell(activity);
        featuredCheck.setTextAndCheck("Featured in Library", false, false);
        featuredCheck.setOnClickListener(v -> featuredCheck.setChecked(!featuredCheck.isChecked()));

        box.addView(titleInput);
        box.addView(slugInput);
        box.addView(categoryInput);
        box.addView(descInput);
        box.addView(makeLabel(activity, "Pack Type:"));
        box.addView(typeSpinner);
        box.addView(makeLabel(activity, "Access Tier:"));
        box.addView(tierSpinner);
        box.addView(featuredCheck);

        new AlertDialog.Builder(activity)
                .setTitle("Create Pack")
                .setView(box)
                .setPositiveButton("Create Draft", (d, w) -> {
                    String title = titleInput.getText().toString().trim();
                    String slug = slugInput.getText().toString().trim().toLowerCase(Locale.US).replaceAll("[^a-z0-9_-]", "_");
                    if (title.isEmpty() || slug.length() < 2) {
                        BulletinFactory.of(this).createErrorBulletin("Enter valid title and slug.").show();
                        return;
                    }
                    JSONObject payload = new JSONObject();
                    try {
                        payload.put("title", title);
                        payload.put("name", slug);
                        payload.put("packType", typeSpinner.getSelectedItem().toString());
                        payload.put("category", categoryInput.getText().toString().trim());
                        payload.put("description", descInput.getText().toString().trim());
                        payload.put("accessTier", tierSpinner.getSelectedItem().toString());
                        payload.put("featured", featuredCheck.isChecked());
                        payload.put("rightsStatus", "approved");
                        payload.put("publicationStatus", "draft");
                    } catch (JSONException ignored) {
                    }
                    repo.createAdminPackAsync(payload, new EvePackRepository.Callback<EvePackModels.AdminPackSummary>() {
                        @Override
                        public void onSuccess(EvePackModels.AdminPackSummary data) {
                            BulletinFactory.of(EveAdminPackManagerFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, "Created draft pack '" + data.title + "'.").show();
                            refreshCatalog();
                        }

                        @Override
                        public void onError(String message, int httpStatus, String serverCode) {
                            BulletinFactory.of(EveAdminPackManagerFragment.this).createErrorBulletin(message).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showTelegramImportDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(10), dp(20), dp(4));

        EditText urlOrSlugInput = makeInput(activity, "Telegram URL or short name (e.g., https://t.me/addstickers/Animals)");
        EditText categoryInput = makeInput(activity, "Category (e.g., Study, Reactions)");
        categoryInput.setText("General");
        EditText evidenceInput = makeInput(activity, "License / Attribution Note");

        Spinner typeSpinner = new Spinner(activity);
        typeSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] { "Auto Detect", "sticker", "emoji" }));
        Spinner tierSpinner = new Spinner(activity);
        tierSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] { "free", "premium" }));
        Spinner rightsSpinner = new Spinner(activity);
        rightsSpinner.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_spinner_dropdown_item,
                new String[] {
                        "eve_owned",
                        "open_license",
                        "creator_permitted",
                        "rights_holder_authorized",
                        "no_redistribution_rights"
                }));

        box.addView(urlOrSlugInput);
        box.addView(categoryInput);
        box.addView(evidenceInput);
        box.addView(makeLabel(activity, "Pack Type:"));
        box.addView(typeSpinner);
        box.addView(makeLabel(activity, "Access Tier:"));
        box.addView(tierSpinner);
        box.addView(makeLabel(activity, "Redistribution Rights Basis:"));
        box.addView(rightsSpinner);

        new AlertDialog.Builder(activity)
                .setTitle("Import from Telegram")
                .setView(box)
                .setPositiveButton("Start Chunked Import", (d, w) -> {
                    String raw = urlOrSlugInput.getText().toString().trim();
                    if (raw.isEmpty()) {
                        BulletinFactory.of(this).createErrorBulletin("Enter a Telegram pack URL or short name.").show();
                        return;
                    }
                    String selectedType = typeSpinner.getSelectedItemPosition() == 0 ? "" : typeSpinner.getSelectedItem().toString();
                    runChunkedTelegramImport(
                            raw,
                            selectedType,
                            categoryInput.getText().toString().trim(),
                            tierSpinner.getSelectedItem().toString(),
                            rightsSpinner.getSelectedItem().toString(),
                            evidenceInput.getText().toString().trim());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runChunkedTelegramImport(
            String packUrlOrSlug,
            String packTypeOverride,
            String category,
            String accessTier,
            String rightsBasis,
            String evidence) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        AlertDialog progressDialog = new AlertDialog.Builder(activity)
                .setTitle("Importing Telegram Pack")
                .setMessage("Contacting EVE Worker Bot API...")
                .setNegativeButton("Hide", null)
                .create();
        progressDialog.show();

        repo.importTelegramPackChunkedAsync(
                packUrlOrSlug, packTypeOverride, category, accessTier, rightsBasis, evidence, 6,
                new EvePackRepository.TelegramImportProgressCallback() {
                    @Override
                    public void onBatch(int batchNumber, EvePackModels.AdminImportResult res, boolean finished) {
                        if (res.redistributionBlocked) {
                            progressDialog.dismiss();
                            new AlertDialog.Builder(activity)
                                    .setTitle("Redistribution Blocked")
                                    .setMessage("Mirroring into EVE Storage was blocked because selected rightsBasis ('"
                                            + rightsBasis + "') does not permit redistribution.\n\nExternal link recorded: " + res.externalReference)
                                    .setPositiveButton("OK", null)
                                    .show();
                            refreshCatalog();
                            return;
                        }
                        String msg = String.format(Locale.US,
                                "Pack: %s (%s)\nBatch #%d • Imported: %d / %d • Failed: %d\nStatus: %s",
                                res.packTitle, res.packType, batchNumber, res.importedCount, res.totalStickers, res.failedCount, res.importStatus);
                        if (progressDialog.isShowing()) {
                            progressDialog.setMessage(msg);
                        }
                        if (finished) {
                            progressDialog.dismiss();
                            BulletinFactory.of(EveAdminPackManagerFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check,
                                            "Import finished: " + res.importedCount + " items (" + res.failedCount + " failed).")
                                    .show();
                            refreshCatalog();
                        }
                    }

                    @Override
                    public void onError(String message, int httpStatus) {
                        if (progressDialog.isShowing()) {
                            progressDialog.dismiss();
                        }
                        BulletinFactory.of(EveAdminPackManagerFragment.this)
                                .createErrorBulletin(message != null ? message : "Import failed.")
                                .show();
                    }
                });
    }

    private void showNotoImportDialog() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        String[] presets = new String[] { "popular", "study" };
        new AlertDialog.Builder(activity)
                .setTitle("Import Google Noto Animated Emoji")
                .setItems(new String[] { "Popular Pack (10 emojis)", "Study Pack (6 emojis)" }, (d, which) -> {
                    repo.importNotoPresetAsync(presets[which], new EvePackRepository.Callback<String>() {
                        @Override
                        public void onSuccess(String message) {
                            BulletinFactory.of(EveAdminPackManagerFragment.this).createSimpleBulletin(R.raw.contact_check, message).show();
                            refreshCatalog();
                        }

                        @Override
                        public void onError(String message, int httpStatus, String serverCode) {
                            BulletinFactory.of(EveAdminPackManagerFragment.this).createErrorBulletin(message).show();
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showPackActionsDialog(EvePackModels.AdminPackSummary pack) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        boolean isPublished = "published".equalsIgnoreCase(pack.publicationStatus);
        boolean isPremium = "premium".equalsIgnoreCase(pack.accessTier);

        String[] options = new String[] {
                "Preview Pack Before Publish",
                "Upload Asset (.json / .tgs / .webm / .webp / .png)",
                isPublished ? "Unpublish Pack" : "Publish Pack",
                pack.enabled ? "Disable Pack" : "Enable Pack",
                isPremium ? "Switch Access Tier -> Free" : "Switch Access Tier -> EVE Premium",
                pack.featured ? "Remove from Featured" : "Mark as Featured",
                "Delete Pack Permanently"
        };

        new AlertDialog.Builder(activity)
                .setTitle(pack.title + " (" + pack.itemCount + " items)")
                .setItems(options, (d, which) -> {
                    switch (which) {
                        case 0:
                            EvePackModels.PackSummary summary = new EvePackModels.PackSummary(
                                    pack.id, pack.name, pack.title, pack.packType, pack.description,
                                    pack.category, pack.featured, pack.itemCount, pack.version, pack.accessTier,
                                    false, pack.rightsStatus, pack.creatorAttribution, pack.licenseAttribution,
                                    pack.source, pack.thumbnailUrl, null, null, pack.createdAt, pack.updatedAt);
                            new EvePackDetailBottomSheet(this, summary, true, null).show();
                            break;
                        case 1:
                            promptAssetUpload(pack);
                            break;
                        case 2:
                            repo.publishAdminPackAsync(pack.id, isPublished ? "unpublished" : "published",
                                    simpleRefreshCallback("Pack " + (isPublished ? "unpublished." : "published.")));
                            break;
                        case 3:
                            repo.toggleAdminPackStatusAsync(pack.id, !pack.enabled,
                                    simpleRefreshCallback("Pack " + (!pack.enabled ? "enabled." : "disabled.")));
                            break;
                        case 4:
                            JSONObject tierBody = new JSONObject();
                            try {
                                tierBody.put("accessTier", isPremium ? "free" : "premium");
                            } catch (JSONException ignored) {}
                            repo.updateAdminPackAsync(pack.id, tierBody, simpleRefreshCallback("Updated access tier."));
                            break;
                        case 5:
                            JSONObject featBody = new JSONObject();
                            try {
                                featBody.put("featured", !pack.featured);
                            } catch (JSONException ignored) {}
                            repo.updateAdminPackAsync(pack.id, featBody, simpleRefreshCallback("Updated featured state."));
                            break;
                        case 6:
                            new AlertDialog.Builder(activity)
                                    .setTitle("Delete '" + pack.title + "'?")
                                    .setMessage("This removes all pack items and media from storage.")
                                    .setPositiveButton("Delete", (d2, w2) -> repo.deleteAdminPackAsync(pack.id,
                                            simpleRefreshCallback("Pack deleted.")))
                                    .setNegativeButton("Cancel", null)
                                    .show();
                            break;
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void promptAssetUpload(EvePackModels.AdminPackSummary pack) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        EditText emojiInput = makeInput(activity, "Fallback Emoji (e.g., 🔥)");
        emojiInput.setText("✨");
        new AlertDialog.Builder(activity)
                .setTitle("Upload Sticker / Emoji Asset")
                .setMessage("Select a .json (Lottie), .tgs, .webm, .webp, or .png file (max 512KB WebM / 300KB Lottie).")
                .setView(emojiInput)
                .setPositiveButton("Choose File", (d, w) -> {
                    pendingUploadPackId = pack.id;
                    String alt = emojiInput.getText().toString().trim();
                    pendingUploadAltEmoji = alt.isEmpty() ? "✨" : alt;
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.setType("*/*");
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(Intent.createChooser(intent, "Select Sticker/Emoji File"), REQ_UPLOAD_ASSET);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        super.onActivityResultFragment(requestCode, resultCode, data);
        if (requestCode != REQ_UPLOAD_ASSET || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Context ctx = getContext();
        if (ctx == null || pendingUploadPackId.isEmpty()) {
            return;
        }
        Uri uri = data.getData();
        try (InputStream is = ctx.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (is == null) return;
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) {
                bos.write(buf, 0, n);
                if (bos.size() > 1024 * 1024) {
                    BulletinFactory.of(this).createErrorBulletin("File exceeds 1MB upload cap.").show();
                    return;
                }
            }
            String mime = ctx.getContentResolver().getType(uri);
            String lowerUri = uri.toString().toLowerCase(Locale.US);
            if (mime == null || mime.isEmpty() || "application/octet-stream".equals(mime)) {
                if (lowerUri.endsWith(".tgs")) mime = "application/x-tgsticker";
                else if (lowerUri.endsWith(".webm")) mime = "video/webm";
                else if (lowerUri.endsWith(".webp")) mime = "image/webp";
                else if (lowerUri.endsWith(".png")) mime = "image/png";
                else mime = "application/json";
            }
            repo.uploadCustomAssetAsync(pendingUploadPackId, pendingUploadAltEmoji, mime, bos.toByteArray(),
                    new EvePackRepository.Callback<EvePackModels.PackItem>() {
                        @Override
                        public void onSuccess(EvePackModels.PackItem item) {
                            BulletinFactory.of(EveAdminPackManagerFragment.this)
                                    .createSimpleBulletin(R.raw.contact_check, "Uploaded " + item.fileFormat + " item.").show();
                            refreshCatalog();
                        }

                        @Override
                        public void onError(String message, int httpStatus, String serverCode) {
                            BulletinFactory.of(EveAdminPackManagerFragment.this).createErrorBulletin(message).show();
                        }
                    });
        } catch (Throwable t) {
            BulletinFactory.of(this).createErrorBulletin("Could not read selected file.").show();
        }
    }

    private EvePackRepository.Callback<Boolean> simpleRefreshCallback(String successMsg) {
        return new EvePackRepository.Callback<Boolean>() {
            @Override
            public void onSuccess(Boolean data) {
                BulletinFactory.of(EveAdminPackManagerFragment.this).createSimpleBulletin(R.raw.contact_check, successMsg).show();
                refreshCatalog();
            }

            @Override
            public void onError(String message, int httpStatus, String serverCode) {
                BulletinFactory.of(EveAdminPackManagerFragment.this).createErrorBulletin(message).show();
            }
        };
    }

    private static EditText makeInput(Context ctx, String hint) {
        EditTextBoldCursor et = new EditTextBoldCursor(ctx);
        et.setHint(hint);
        et.setSingleLine(true);
        et.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        et.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint));
        et.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        return et;
    }

    private static TextView makeLabel(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
        tv.setPadding(0, dp(8), 0, dp(2));
        return tv;
    }
}
