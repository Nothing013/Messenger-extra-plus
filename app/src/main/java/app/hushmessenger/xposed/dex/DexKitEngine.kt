package app.hushmessenger.xposed.dex

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import org.luckypray.dexkit.result.ClassData
import org.luckypray.dexkit.result.MethodData
import java.lang.reflect.Modifier

/**
 * Runtime translation of the HushMessenger Tier 1 fingerprints.
 *
 * Every query below is taken from the patch sources in the HushMessenger
 * repository and keeps their shape checks. Anchors are method-level string
 * literals, because that is how HushMessenger discovers the methods too:
 * a gate is a boolean method that names its plugin, not a method on the plugin
 * class. Queries are run one anchor at a time so the result never depends on
 * whether DexKit's string list means "all" or "any".
 *
 * Nothing is fatal. A query that finds nothing leaves the stock behaviour in
 * place and is recorded as scanned for this Messenger version.
 */
class DexKitEngine(private val apkPath: String) {

    private val results = mutableMapOf<String, MutableList<MethodRef>>()

    /**
     * Runs the queries for [needed] and returns the resolved descriptors.
     *
     * Returns null only when the DexKit bridge itself could not be created, so
     * the caller does not cache an empty result over a working scan. A single
     * fingerprint that does not exist is not an error: it is logged and stays
     * unresolved for this Messenger version.
     */
    fun resolve(needed: Set<String>): Map<String, List<MethodRef>>? {
        val bridge = runCatching { DexKitBridge.create(apkPath) }.getOrElse {
            Log.e(TAG, "DexKitBridge.create failed for $apkPath", it)
            return null
        }

        bridge.use { dex ->
            try {
                readReceipts(dex, needed)
                typing(dex, needed)
                ads(dex, needed)
                metaAi(dex, needed)
                stories(dex, needed)
                screenshots(dex, needed)
            } catch (t: Throwable) {
                Log.e(TAG, "DexKit query batch failed", t)
            }
        }

        return results.mapValues { entry -> entry.value.toList() }
    }

    // ---------------------------------------------------------------- receipts

    private fun readReceipts(dex: DexKitBridge, needed: Set<String>) {
        if ("read_seen" !in needed) return

        // Static (FbUserSession, <own class>, String)V that names the scheduled
        // Seen job and its trace. Returning early keeps Messenger's own state
        // but never broadcasts Seen.
        find(
            dex, "read_seen", needed,
        ) {
            modifiers = Modifier.STATIC
            returnType("void")
            paramCount(3)
            paramTypes(FB_USER_SESSION, null, "java.lang.String")
            usingStrings(SEEN_JOB)
        }
    }

    // ------------------------------------------------------------------ typing

    private fun typing(dex: DexKitBridge, needed: Set<String>) {
        if ("typing_mailbox" in needed) {
            // Encrypted chats: instance (thread id, typing) method that names
            // the msys mailbox call. The hook rewrites the boolean to false.
            find(
                dex, "typing_mailbox", needed,
            ) {
                paramCount(2)
                paramTypes("java.lang.String", "boolean")
                usingStrings(TYPING_MAILBOX)
            }
        }

        if ("typing_run" !in needed) return

        // Normal chats: the two synthetic Runnables behind
        // ConversationTypingContext. Both publish the `/t_st` MSYS typing
        // indicator, so matching the wire constant at class level plus a plain
        // run()V is stable across builds: the mailbox wrapper and the Redex
        // names move, the protocol string does not. The send runnable is picked
        // by its Redex original name at hook time; the cancel runnable is never
        // hooked.
        val runnables = runCatching {
            dex.findClass {
                matcher {
                    usingStrings(TYPING_EVENT)
                    methods {
                        add {
                            name = "run"
                            paramCount(0)
                            returnType("void")
                        }
                    }
                }
            }.flatMap { clazz ->
                clazz.methods.filter {
                    it.name == "run" &&
                        it.paramTypeNames.isEmpty() &&
                        it.returnTypeName == "void"
                }
            }
        }.getOrElse {
            Log.w(TAG, "typing runnable query failed: ${it.message}")
            emptyList()
        }
        add("typing_run", needed, runnables)
    }

    // --------------------------------------------------------------------- ads

    private fun ads(dex: DexKitBridge, needed: Set<String>) {
        gates(
            dex, "ads.banner", needed,
            "com.facebook.messaging.nativepagereply.plugins.adscontextprovider.threadviewbanner.CTMAdsThreadViewBannerImplementation",
        )
        gates(
            dex, "ads.inbox_promo", needed,
            "com.facebook.messaging.quickpromotion.plugins.threadlist.QuickpromotionThreadlistKillSwitch",
            "com.facebook.messaging.quickpromotion.plugins.threadlistmsys.QuickpromotionThreadlistmsysKillSwitch",
        )
        gates(
            dex, "ads.chat_promo", needed,
            "com.facebook.messaging.quickpromotion.plugins.threadview.QuickpromotionThreadviewKillSwitch",
            "com.facebook.messaging.quickpromotion.plugins.threadviewmsys.QuickpromotionThreadviewmsysKillSwitch",
        )

        // The click-to-message ad deep link: static (context, FbUserSession,
        // four Strings)V that fills ad_id and page_id, then logs.
        find(
            dex, "ads.entry", needed,
        ) {
            modifiers = Modifier.STATIC
            returnType("void")
            paramCount(6)
            paramTypes(
                null,
                FB_USER_SESSION,
                "java.lang.String",
                "java.lang.String",
                "java.lang.String",
                "java.lang.String",
            )
            usingStrings(AD_ID)
        }

        if ("ads.tracker" !in needed && "ads.logger" !in needed) return

        // Inbox row visibility tracker callback: instance (<rows>, boolean)V
        // that names both the tracker and its inbox2_vr event.
        val tracker = find(
            dex, "ads.tracker", needed,
        ) {
            returnType("void")
            paramCount(2)
            paramTypes(null, "boolean")
            usingStrings(INBOX_EVENT)
        }.firstOrNull()

        if ("ads.logger" !in needed || tracker == null) return

        // Skip only the logger call, never the tracker itself. The logger is a
        // shared analytics entry point, so it is accepted only when exactly one
        // non-framework, one-argument void call exists and it is not a
        // constructor. The hook then checks the event name at runtime.
        val loggers = tracker.invokes
            .filter {
                !it.isConstructor &&
                    !it.isStaticInitializer &&
                    it.returnTypeName == "void" &&
                    it.paramTypeNames.size == 1 &&
                    !it.className.startsWith("java.") &&
                    !it.className.startsWith("android.") &&
                    !it.className.startsWith("kotlin.") &&
                    !it.className.contains("ReqContext")
            }
            .distinctBy { it.identity() }

        if (loggers.size == 1) {
            results.getOrPut("ads.logger") { mutableListOf() }
                .let { list -> if (list.none { it == loggers[0].toMethodRef() }) list += loggers[0].toMethodRef() }
        } else {
            Log.w(TAG, "ads logger ambiguous (${loggers.size} candidates); leaving the tracker untouched")
        }
    }

    // ----------------------------------------------------------------- Meta AI

    private fun metaAi(dex: DexKitBridge, needed: Set<String>) {
        gates(
            dex, "ai.tab", needed,
            "com.facebook.messaging.aibot.plugins.tab.tabcontent.MetaAiTabContentImplementation",
        )
        gates(
            dex, "ai.muse", needed,
            "com.facebook.messaging.aiagent.plugins.tab.tabcontent.MuseTabContentImplementation",
        )
        gates(
            dex, "ai.toolbar", needed,
            "com.facebook.messaging.inbox.tab.plugins.core.tabtoolbarbutton.aihomebutton.AiHomeButtonKillSwitch",
        )
        gates(
            dex, "ai.stickers", needed,
            "com.facebook.stickers.keyboardls.generatedtab.plugins.core.KeyboardlsGeneratedtabCoreKillSwitch",
            // The second gate covers the composer's generated search row.
            "com.facebook.messaging.suggestedkeyboard.plugins.core.composer.rows.genai.GenAiSearchSuggestedRow",
        )

        // The summary eligibility check is the single (ThreadKey, Capabilities,
        // long, boolean, boolean)Z method called by the handler that names the
        // floating pill. The pill name and the caller are in different methods
        // of the same class, so resolve the class first, then look at every
        // method it declares instead of only the method that names the pill.
        if ("ai.summary" in needed) {
            val classes: List<ClassData> = runCatching {
                dex.findClass {
                    matcher { usingStrings(AI_SUMMARY_PILL) }
                }
            }.getOrElse {
                Log.w(TAG, "ai summary query failed: ${it.message}")
                emptyList()
            }
            val checks = classes
                .flatMap { it.methods }
                .flatMap { it.invokes }
                .filter {
                    it.returnTypeName == "boolean" &&
                        it.paramTypeNames == AI_SUMMARY_PARAMS
                }
                .distinctBy { it.identity() }
            if (checks.size == 1) {
                results["ai.summary"] = mutableListOf(checks[0].toMethodRef())
            } else if (checks.size > 1) {
                Log.w(TAG, "ai summary ambiguous (${checks.size} candidates)")
            }
        }

        // The @-list dispatchers: instance (query, ImmutableList.Builder)V that
        // name one of the three Meta AI mention sections.
        for (plugin in AI_MENTION_PLUGINS) {
            find(
                dex, "ai.mentions", needed,
            ) {
                returnType("void")
                paramCount(2)
                paramTypes(null, MENTION_LIST_BUILDER)
                usingStrings(plugin)
            }
        }
    }

    // -------------------------------------------------------- Stories / Reels

    private fun stories(dex: DexKitBridge, needed: Set<String>) {
        // The inbox row of stories and notes above the chats.
        gates(
            dex, "stories.gate", needed,
            "com.facebook.messaging.friendsinboxunit.plugins.inboxunit.FriendsInboxUnitKillSwitch",
        )
        gates(
            dex, "stories.reels_badge", needed,
            "com.facebook.messaging.reels.plugins.badge.ReelsBadgeKillSwitch",
        )

        // A finished story page: public instance ()V that logs
        // story_playback_finished and then advances the viewer. Returning early
        // keeps the page on screen instead of moving to the next card.
        if ("stories.finish" in needed) {
            val completions: List<MethodData> = find(
                dex, "stories.finish", needed,
            ) {
                returnType("void")
                paramCount(0)
                usingStrings(STORY_FINISHED)
            }
            val instance = completions.filter { it.modifiers and Modifier.STATIC == 0 }
            results["stories.finish"] = instance.map { it.toMethodRef() }.toMutableList()
            if (instance.isEmpty()) results.remove("stories.finish")
        }
    }

    // --------------------------------------------------------------- screenshots

    private fun screenshots(dex: DexKitBridge, needed: Set<String>) {
        // The helper that locks a media viewer's window with FLAG_SECURE:
        // instance (Window)V, adds a 0x2000 literal and calls Window.addFlags.
        if ("screenshots.window_helper" in needed) {
            val found = findMethods(dex, "screenshots.window_helper") {
                matcher {
                    returnType("void")
                    paramCount(1)
                    paramTypes("android.view.Window")
                    usingNumbers(FLAG_SECURE)
                    invokeMethods {
                        add {
                            name = "addFlags"
                            declaredClass("android.view.Window")
                            returnType("void")
                            paramCount(1)
                            paramTypes("int")
                        }
                    }
                }
            }
            val instance = found.filter { it.modifiers and Modifier.STATIC == 0 }
            if (instance.isNotEmpty()) {
                results["screenshots.window_helper"] =
                    instance.map { it.toMethodRef() }.toMutableList()
            }
        }

        // Android 14's screen capture callback: Messenger's implementation of
        // Activity.ScreenCaptureCallback turns a capture into the in-chat notice.
        if ("screenshots.capture" in needed) {
            val classes: List<ClassData> = runCatching {
                dex.findClass {
                    matcher {
                        interfaces { add(SCREEN_CAPTURE_CALLBACK) }
                        methods {
                            add {
                                name = "onScreenCaptured"
                                paramCount(0)
                                returnType("void")
                            }
                        }
                    }
                }
            }.getOrElse {
                Log.w(TAG, "screenshot callback query failed: ${it.message}")
                emptyList()
            }
            add(
                "screenshots.capture",
                needed,
                classes.flatMap { clazz ->
                    clazz.methods.filter {
                        it.name == "onScreenCaptured" &&
                            it.paramTypeNames.isEmpty() &&
                            it.returnTypeName == "void"
                    }
                },
            )
        }
    }

    // ----------------------------------------------------------------- helpers

    /**
     * A plugin gate: boolean, no parameters or one (the static form takes the
     * declaring class), naming the plugin implementation. False is Messenger's
     * own disabled answer.
     */
    private fun gates(
        dex: DexKitBridge,
        key: String,
        needed: Set<String>,
        vararg anchors: String,
    ) {
        for (anchor in anchors) {
            find(
                dex, key, needed,
            ) {
                returnType("boolean")
                paramCount(0, 1)
                usingStrings(anchor)
            }
        }
    }

    private fun find(
        dex: DexKitBridge,
        key: String,
        needed: Set<String>,
        build: MethodMatcher.() -> Unit,
    ): List<MethodData> {
        if (key !in needed) return emptyList()
        val refs = findMethods(dex, key) { matcher(build) }
        add(key, needed, refs)
        return refs
    }

    private fun findMethods(
        dex: DexKitBridge,
        key: String,
        init: FindMethod.() -> Unit,
    ): List<MethodData> = runCatching {
        dex.findMethod(init)
    }.getOrElse {
        Log.w(TAG, "query $key failed: ${it.message}")
        emptyList()
    }

    private fun add(key: String, needed: Set<String>, refs: List<MethodData>) {
        if (key !in needed || refs.isEmpty()) return
        val list = results.getOrPut(key) { mutableListOf() }
        for (ref in refs) {
            val methodRef = ref.toMethodRef()
            if (list.none { it == methodRef }) list += methodRef
        }
    }

    private fun MethodData.identity(): String =
        "$className|$name|${paramTypeNames.joinToString(",")}|$returnTypeName"

    companion object {
        private const val TAG = "HushXposed"

        private const val FB_USER_SESSION = "com.facebook.auth.usersession.FbUserSession"
        private const val MENTION_LIST_BUILDER = "com.google.common.collect.ImmutableList\$Builder"
        private const val SCREEN_CAPTURE_CALLBACK = "android.app.Activity\$ScreenCaptureCallback"

        private const val FLAG_SECURE = 0x2000

        /**
         * Redex keeps the pre-obfuscation class name in a static field. DexKit's
         * `usingStrings` only indexes `const-string` instructions, so the value
         * cannot be matched in a query; the class is narrowed by shape and the
         * name is checked reflectively when the hook is installed.
         */
        internal const val TYPING_RUN_ORIGINAL = "ConversationTypingContext\$sendActiveStateRunnable\$1"

        private const val SEEN_JOB = "MARK_THREAD_AS_SEEN_JOB"
        private const val TYPING_MAILBOX = "setTypingIndicatorForThreadWithThreadIdentifier"
        private const val TYPING_EVENT = "/t_st"
        private const val AD_ID = "ad_id"
        private const val INBOX_EVENT = "inbox2_vr"
        private const val AI_SUMMARY_PILL = "AiSummaryFloatingPillEventHandler"
        private const val STORY_FINISHED = "story_playback_finished"

        private val AI_SUMMARY_PARAMS = listOf(
            "com.facebook.messaging.model.threadkey.ThreadKey",
            "com.facebook.xapp.messaging.capability.vector.Capabilities",
            "long",
            "boolean",
            "boolean",
        )

        private val AI_MENTION_PLUGINS = listOf(
            "messaging.aibot.core.mentionai.MentionAiRowSupplier",
            "messaging.aibot.core.imagine.ImagineRowSupplier",
            "messaging.communitymessaging.ai.mentionrowsupplier.CommunityAiRowSupplier",
        )
    }
}
