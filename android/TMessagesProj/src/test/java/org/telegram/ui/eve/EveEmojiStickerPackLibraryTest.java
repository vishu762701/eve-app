package org.telegram.ui.eve;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Application;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.eve.media.EveAdminPackManagerFragment;
import org.telegram.ui.eve.media.EvePackAssetView;
import org.telegram.ui.eve.media.EvePackCacheManager;
import org.telegram.ui.eve.media.EvePackDetailBottomSheet;
import org.telegram.ui.eve.media.EvePackLibraryFragment;
import org.telegram.ui.eve.media.EvePackModels;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Automated JVM regression suite for EVE's independent Emoji & Sticker Pack Library:
 *   - Catalogue / manifest / admin JSON model parsing
 *   - Worker relative asset URL resolution
 *   - SHA-256 verification and corruption rejection
 *   - Incremental update reuse of unchanged item digests
 *   - Offline removal & clean zero-Telegram-account execution
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, manifest = Config.NONE, application = Application.class)
public class EveEmojiStickerPackLibraryTest {

    private Context context;
    private File tempCacheDir;
    private EvePackCacheManager cacheManager;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        ApplicationLoader.applicationContext = context;
        tempCacheDir = new File(context.getCacheDir(), "eve_pack_test_" + System.nanoTime());
        cacheManager = new EvePackCacheManager(tempCacheDir);
    }

    @After
    public void tearDown() {
        if (cacheManager != null) {
            cacheManager.clearAllCache();
        }
        if (tempCacheDir != null && tempCacheDir.exists()) {
            tempCacheDir.delete();
        }
    }

    @Test
    public void packCatalogueStringsAndClasses_areIntactAndFreeOfTelegramLoginDependency() {
        assertFalse(UserConfig.getInstance(0).isClientActivated());
        assertEquals("Emoji & Sticker Packs", context.getString(R.string.EveEmojiStickerPacks));
        assertEquals("Stickers", context.getString(R.string.EveStickerPacksTab));
        assertEquals("Emoji Packs", context.getString(R.string.EveEmojiPacksTab));
        assertEquals("Installed", context.getString(R.string.EveInstalledPacksTab));
        assertEquals("Unlock", context.getString(R.string.EveUnlockPack));

        assertNotNull(EvePackLibraryFragment.class);
        assertNotNull(EvePackDetailBottomSheet.class);
        assertNotNull(EvePackAssetView.class);
        assertNotNull(EveAdminPackManagerFragment.class);
    }

    @Test
    public void packSummaryAndManifestJsonParsing_handlesStickersEmojisAndRelativeUrls() throws Exception {
        JSONObject itemObj = new JSONObject()
                .put("id", "item_1")
                .put("packId", "pack_study_1")
                .put("index", 0)
                .put("altEmoji", "🎓")
                .put("kind", "video")
                .put("fileFormat", "webm")
                .put("mimeType", "video/webm")
                .put("isAnimated", true)
                .put("isVideo", true)
                .put("byteSize", 1024)
                .put("sha256", "abc123")
                .put("assetUrl", "/api/emoji/assets/item_1");

        JSONObject summaryObj = new JSONObject()
                .put("id", "pack_study_1")
                .put("name", "study_stickers")
                .put("title", "Study Stickers")
                .put("packType", "sticker")
                .put("category", "Study")
                .put("featured", true)
                .put("itemCount", 8)
                .put("version", 2)
                .put("accessTier", "premium")
                .put("isLockedForUser", true)
                .put("previews", new JSONArray().put(itemObj));

        EvePackModels.PackSummary summary = EvePackModels.PackSummary.fromJson(summaryObj);
        assertTrue(summary.isStickerPack());
        assertFalse(summary.isEmojiPack());
        assertTrue(summary.isPremium());
        assertTrue(summary.isLockedForUser);
        assertEquals("8 stickers", summary.countLabel());
        assertEquals("https://eve-backend.anyqueairdrop.workers.dev/api/emoji/assets/item_1",
                summary.previews.get(0).assetUrl);
        assertEquals("webm", summary.previews.get(0).fileExtension());
    }

    @Test
    public void cacheManager_verifiesSha256ReusesUnchangedAssetsAndRemovesCleanly() throws Exception {
        byte[] lottieBytes = "{\"v\":\"5.7.1\",\"layers\":[]}".getBytes(StandardCharsets.UTF_8);
        String lottieSha = EvePackCacheManager.sha256Hex(lottieBytes);

        byte[] webpBytes = "RIFF0000WEBPdata".getBytes(StandardCharsets.UTF_8);
        String webpSha = EvePackCacheManager.sha256Hex(webpBytes);

        List<EvePackModels.PackItem> itemsV1 = new ArrayList<>();
        itemsV1.add(new EvePackModels.PackItem("it_lottie", "pk_1", 0, "🔥", "lottie", "lottie", "application/json",
                true, false, lottieBytes.length, lottieSha, "/api/emoji/assets/it_lottie", "", true, ""));
        EvePackModels.PackManifest manifestV1 = new EvePackModels.PackManifest(
                "pk_1", "starter_pack", "Starter Pack", "emoji", 1, 1, lottieBytes.length, "m1", itemsV1);

        AtomicInteger fetchCount = new AtomicInteger(0);
        EvePackModels.InstalledPackRecord installedV1 = cacheManager.installPack(manifestV1, item -> {
            fetchCount.incrementAndGet();
            return lottieBytes;
        }, null);
        assertEquals(1, fetchCount.get());
        assertEquals(1, installedV1.version);
        assertTrue(cacheManager.isPackInstalled("pk_1"));

        File localV1 = cacheManager.getLocalAssetFile("pk_1", itemsV1.get(0));
        assertNotNull(localV1);
        assertTrue(localV1.exists());

        // v2 keeps it_lottie (identical SHA-256 -> reused!) and adds it_webp
        List<EvePackModels.PackItem> itemsV2 = new ArrayList<>();
        itemsV2.add(itemsV1.get(0));
        itemsV2.add(new EvePackModels.PackItem("it_webp", "pk_1", 1, "🏆", "static", "webp", "image/webp",
                false, false, webpBytes.length, webpSha, "/api/emoji/assets/it_webp", "", true, ""));
        EvePackModels.PackManifest manifestV2 = new EvePackModels.PackManifest(
                "pk_1", "starter_pack", "Starter Pack", "emoji", 2, 2, lottieBytes.length + webpBytes.length, "m2", itemsV2);

        fetchCount.set(0);
        EvePackModels.InstalledPackRecord installedV2 = cacheManager.installPack(manifestV2, item -> {
            fetchCount.incrementAndGet();
            assertEquals("it_webp", item.id);
            return webpBytes;
        }, null);
        assertEquals("Unchanged SHA-256 asset should be reused without re-fetching", 1, fetchCount.get());
        assertEquals(2, installedV2.version);
        assertEquals(2, installedV2.itemCount);
        assertTrue(cacheManager.getTotalCacheBytes() > 0);

        // Tampered bytes must fail SHA-256 verification cleanly without replacing v2
        List<EvePackModels.PackItem> corruptItems = new ArrayList<>();
        corruptItems.add(new EvePackModels.PackItem("it_bad", "pk_1", 0, "⚠️", "lottie", "lottie", "application/json",
                true, false, 12, "0000000000000000000000000000000000000000000000000000000000000000", "/api/emoji/assets/it_bad", "", true, ""));
        EvePackModels.PackManifest corruptManifest = new EvePackModels.PackManifest(
                "pk_1", "starter_pack", "Starter Pack", "emoji", 3, 1, 12, "m3", corruptItems);
        try {
            cacheManager.installPack(corruptManifest, item -> "{\"v\":\"bad\"}".getBytes(StandardCharsets.UTF_8), null);
            fail("Expected SHA-256 verification failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256"));
        }
        assertEquals(2, cacheManager.getInstalledPack("pk_1").version);

        // Clean removal removes offline files and index entry
        assertTrue(cacheManager.removePack("pk_1"));
        assertFalse(cacheManager.isPackInstalled("pk_1"));
        assertNull(cacheManager.getLocalAssetFile("pk_1", itemsV2.get(0)));
    }
}
