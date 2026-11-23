package com.eve.app.ui.profile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrShareActivityDefaultStateTest {

    @Test
    fun qrShareActivityStartsWithDefaultMonochromePaletteAndNoForcedEmojiLoop() {
        val src = File("src/main/java/com/eve/app/ui/profile/QrShareActivity.kt").readText()
        assertTrue(
            "QrShareActivity must default to palette 0 matching Telegram QR reference",
            src.contains("setPalette(savedInstanceState?.getInt(\"qr_palette\", 0) ?: 0)")
        )
        assertFalse(
            "QrShareActivity must not force continuous sparkle emoji playback on initial launch",
            src.contains("QrEmojiPattern.standard(\"✨\")")
        )
    }
}
