package app.messengerhook.xposed.core

/** RemotePreferences group holding the user's switches. Written by the settings app, read by the hook. */
const val SETTINGS_GROUP = "messenger_hook_settings"

/**
 * Settings key the module app bumps to ask the hook to ignore its descriptor
 * cache once. The hook compares it with the token stored in its own cache file.
 */
const val RESCAN_KEY = "rescan_at"

const val TARGET_PACKAGE = "com.facebook.orca"

data class Feature(
    val key: String,
    val title: String,
    val summary: String,
)

/**
 * Tier 1 plus the first Tier 2 control. Every switch starts off; nothing is
 * hooked until the user opts in.
 *
 * Keep these keys stable: they are persisted in LSPosed's remote preferences and
 * are the same keys the hook reads.
 */
object Features {
    const val READ_RECEIPTS = "read_receipts"
    const val TYPING = "typing"
    const val SCREENSHOTS = "screenshots"
    const val ADS = "ads"
    const val META_AI = "meta_ai"
    const val STORIES = "stories"
    const val KEEP_UNSENT = "keep_unsent"

    val all: List<Feature> = listOf(
        Feature(
            READ_RECEIPTS,
            "Hide read receipts",
            "Opening a chat no longer sends Seen. Messenger still keeps the chat " +
                "unread on this phone until you turn this off.",
        ),
        Feature(
            TYPING,
            "Hide typing indicator",
            "Stops Messenger from telling other people that you are typing. " +
                "This also covers end-to-end encrypted chats.",
        ),
        Feature(
            SCREENSHOTS,
            "Allow screenshots",
            "Lets you capture view-once photos, Quicksnap and other protected media, " +
                "and stops Messenger's screenshot notices.",
        ),
        Feature(
            ADS,
            "Hide ads and promotions",
            "Hides the business-chat ad banner and inbox/chat promotions, and stops " +
                "ad deep-link and impression logging.",
        ),
        Feature(
            META_AI,
            "Hide Meta AI",
            "Hides the Meta AI tab, toolbar button, AI sticker tools, @-mention rows " +
                "and the unread-message summary offer.",
        ),
        Feature(
            STORIES,
            "Hide Stories and Reels",
            "Hides the row of stories and notes above your chats and the Reels badge, " +
                "and keeps a story on screen when it ends.",
        ),
        Feature(
            KEEP_UNSENT,
            "Keep unsent messages",
            "Keeps a message in the chat after it is unsent and marks it with " +
                "[unsent]. In end-to-end encrypted chats the message stays in its " +
                "notification instead.",
        ),
    )

    fun byKey(key: String): Feature? = all.firstOrNull { it.key == key }
}
