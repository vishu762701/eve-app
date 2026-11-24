package com.eve.app.ui.settings

import org.telegram.ui.SettingsActivity as TelegramSettingsActivity

data class SettingsSearchEntry(
    val rowId: Int,
    val title: String,
    val subtitle: String,
    val keywords: List<String>
)

/**
 * Search index for [SettingsActivity.onSearchClicked], mapping queries to live EVE settings rows.
 */
object SettingsSearchIndex {

    val allEntries: List<SettingsSearchEntry> = listOf(
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_ACCOUNT,
            "Account",
            "Name, Username, Phone, Bio, Birthday",
            listOf("profile", "name", "username", "phone", "mobile", "bio", "birthday", "photo")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_CHAT,
            "Appearance & Theme",
            "Day/Night mode, Telegram themes, Font",
            listOf("theme", "dark", "night", "day", "appearance", "color", "wallpaper", "chat settings")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_PRIVACY,
            "Privacy & Security",
            "Passcode lock, Fingerprint, App security",
            listOf("privacy", "security", "passcode", "pin", "lock", "biometric", "fingerprint")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_NOTIFICATIONS,
            "Notifications",
            "Exam alerts, Test reminders, Inbox",
            listOf("notifications", "alerts", "reminders", "sounds", "badges")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_DATA,
            "Data and Storage",
            "Storage usage, Keep media, Clear cache",
            listOf("data", "storage", "cache", "clear", "disk", "media")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_POWER,
            "Power Saving",
            "Reduce animations and battery usage",
            listOf("power", "battery", "animations", "transitions", "lite", "performance")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_LANGUAGE,
            "Language",
            "App display language",
            listOf("language", "locale", "hindi", "english", "translate")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_TARGET_EXAMS,
            "Target Exams",
            "Manage your active exam categories",
            listOf("target", "exams", "categories", "ssc", "rpsc", "upsc", "railway")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_ASK,
            "Ask a Question",
            "Contact EVE Support & send feedback",
            listOf("help", "support", "question", "feedback", "contact", "bug")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_FAQ,
            "EVE FAQ & About",
            "Common questions and app information",
            listOf("faq", "about", "version", "features")
        ),
        SettingsSearchEntry(
            TelegramSettingsActivity.ROW_POLICY,
            "Privacy Policy",
            "Data handling and user privacy terms",
            listOf("policy", "terms", "legal", "privacy")
        )
    )

    fun filter(query: String): List<SettingsSearchEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return allEntries
        return allEntries.filter { entry ->
            entry.title.lowercase().contains(q) ||
                entry.subtitle.lowercase().contains(q) ||
                entry.keywords.any { it.contains(q) }
        }
    }
}
