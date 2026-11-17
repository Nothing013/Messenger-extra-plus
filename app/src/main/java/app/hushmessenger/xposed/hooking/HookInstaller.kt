package app.hushmessenger.xposed.hooking

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import app.hushmessenger.xposed.core.FeatureState
import app.hushmessenger.xposed.core.Features
import app.hushmessenger.xposed.dex.DexKitEngine
import app.hushmessenger.xposed.dex.MethodRef
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Chain
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * Translates HushMessenger's register-level injections into interceptor hooks.
 *
 * Xposed cannot edit DEX, so every patch becomes one of:
 *  - return early (void/object) by not calling chain.proceed(),
 *  - replace the result by not calling proceed() and returning a value,
 *  - rewrite arguments and call proceed(newArgs),
 *  - hook a callee (Window flags, ad logger) instead of skipping mid-method code.
 */
class HookInstaller(
    private val module: XposedModule,
    private val classLoader: ClassLoader,
    private val state: FeatureState,
) {

    private val stringFieldCache = ConcurrentHashMap<Class<*>, List<Field>>()

    fun install(resolved: Map<String, List<MethodRef>>) {
        // Each feature installs in its own try/catch. A broken fingerprint,
        // reflection failure or unexpected null in one feature must not stop
        // the remaining features from installing.
        isolated("read_receipts", Features.READ_RECEIPTS) { installReadReceipts(resolved) }
        isolated("typing", Features.TYPING) { installTyping(resolved) }
        isolated("ads", Features.ADS) { installAds(resolved) }
        isolated("meta_ai", Features.META_AI) { installMetaAi(resolved) }
        isolated("screenshots", Features.SCREENSHOTS) { installScreenshots(resolved) }
        isolated("stories", Features.STORIES) { installStories(resolved) }
    }

    /**
     * Runs one feature's installer in isolation.
     *
     * Hook *bodies* are already built with [XposedInterface.ExceptionMode.PROTECTIVE],
     * so a throw inside a hook falls back to stock behaviour. This guard covers
     * installer-time work as well (reflection, class lookups, argument parsing),
     * where an exception would otherwise abort every later feature.
     */
    private inline fun isolated(name: String, key: String, block: () -> Unit) {
        if (!state.isOn(key)) return
        runCatching(block).onFailure {
            module.log(Log.ERROR, TAG, "feature '$name' failed to install; continuing", it)
        }
    }

    // ------------------------------------------------------------------ privacy

    private fun installReadReceipts(resolved: Map<String, List<MethodRef>>) {
        // The static Seen broadcast is skipped before it reaches the server.
        // Messenger's local read state still updates; only the broadcast stops.
        // v1 does not replay a held-back Seen on reply.
        hookAll(resolved, "read_seen", "read_receipts.seen") { null }
    }

    private fun installTyping(resolved: Map<String, List<MethodRef>>) {
        // Encrypted chats: keep the call, force the "typing" boolean to false.
        // Messenger treats that as "stopped typing", which is safe.
        hookAll(resolved, "typing_mailbox", "typing.mailbox", limit = 4) { chain ->
            val args = chain.args.toMutableList()
            if (args.size >= 2 && args[1] == true) args[1] = false
            chain.proceed(args.toTypedArray())
        }

        // Normal chats: the typing context's synthetic Runnable. The engine
        // returns both the send and the cancel runnable (they share the `/t_st`
        // publish shape), so the Redex original name is what selects the send
        // one. A lone unnamed candidate is the only accepted fallback.
        hookByOriginalName(
            resolved = resolved,
            key = "typing_run",
            tag = "typing.run",
            expectedName = DexKitEngine.TYPING_RUN_ORIGINAL,
        ) { null }
    }

    // ---------------------------------------------------------------------- ads

    private fun installAds(resolved: Map<String, List<MethodRef>>) {
        for (key in listOf("ads.banner", "ads.inbox_promo", "ads.chat_promo")) {
            hookAll(resolved, key, "$key.gate") { false }
        }

        // The click-to-message ad deep link fills ids and logs; skip it whole.
        hookAll(resolved, "ads.entry", "ads.entry") { null }

        // The engine resolves the single one-argument void logger call made by
        // the inbox visibility tracker. That logger is shared with every other
        // analytics event, so the hook only skips the impression event and
        // lets everything else through untouched.
        hookUnique(resolved, "ads.logger", "ads.logger") { chain ->
            val event = chain.args.firstOrNull()
            if (event != null && isInboxImpression(event)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    /**
     * Whether [event] is the tracker's `inbox2_vr` impression.
     *
     * The event name is one of the object's String fields (Redex keeps the
     * shape, not the field names). Reading only String fields avoids touching
     * unrelated analytics payloads and needs no Messenger classes.
     */
    private fun isInboxImpression(event: Any): Boolean =
        stringFields(event.javaClass).any { field ->
            runCatching { field.get(event) as? String }.getOrNull() == INBOX_IMPRESSION_EVENT
        }

    private fun stringFields(clazz: Class<*>): List<Field> =
        stringFieldCache.getOrPut(clazz) {
            val fields = mutableListOf<Field>()
            var current: Class<*>? = clazz
            while (current != null) {
                for (field in current.declaredFields) {
                    if (field.type == String::class.java) {
                        runCatching { field.isAccessible = true }
                        fields += field
                    }
                }
                current = current.superclass
            }
            fields
        }

    // ------------------------------------------------------------------ Meta AI

    private fun installMetaAi(resolved: Map<String, List<MethodRef>>) {
        for (key in listOf("ai.tab", "ai.muse", "ai.toolbar", "ai.stickers")) {
            hookAll(resolved, key, "$key.gate") { false }
        }

        // The single eligibility check behind every "Summarize unread" pill.
        hook(resolved, "ai.summary", "ai.summary.gate") { false }

        // The @-list dispatchers that build Meta AI / imagine rows. Returning
        // early leaves the other mention rows to Messenger.
        hookAll(resolved, "ai.mentions", "ai.mentions") { null }
    }

    // --------------------------------------------------------------- screenshots

    private fun installScreenshots(resolved: Map<String, List<MethodRef>>) {
        // 1. Strip FLAG_SECURE while a protected viewer is on the stack.
        hookWindowFlags()

        // 2. The helper that locks media viewers' windows, and Android 14's
        // capture callback, are matched by DexKit. Both are optional.
        hookUnique(resolved, "screenshots.window_helper", "screenshot.window_helper") { null }
        hookAll(resolved, "screenshots.capture", "screenshot.capture") { null }

        hookViewer(
            "com.facebook.messaging.media.ephemeralmedia.viewer.EphemeralMediaViewerFragment",
            "ephemeral.resume",
        ) { clazz ->
            listOfNotNull(
                runCatching { clazz.getDeclaredMethod("onResume") }.getOrNull(),
                clazz.declaredMethods.firstOrNull {
                    it.returnType == Dialog::class.java &&
                        it.parameterTypes.size == 1 &&
                        it.parameterTypes[0] == Bundle::class.java
                },
            )
        }

        hookViewer(
            "com.facebook.messaging.quicksnap.consumption.viewer.MsgrQuicksnapViewerFragment",
            "quicksnap.createView",
        ) { clazz ->
            listOfNotNull(
                runCatching {
                    clazz.getDeclaredMethod(
                        "onCreateView",
                        LayoutInflater::class.java,
                        ViewGroup::class.java,
                        Bundle::class.java,
                    )
                }.getOrNull(),
            )
        }

        // 3. Stop the screenshot notice. The callback Messenger registers for
        // Activity screen captures is obfuscated; the content observer is a
        // stable, public entry point and covers the same report path. Both
        // onChange overloads are candidates, so match by name and return type.
        runCatching {
            val observer = Class.forName(
                "com.facebook.screenshot.ScreenshotContentObserver",
                false,
                classLoader,
            )
            val callbacks = observer.declaredMethods.filter {
                it.name == "onChange" && it.returnType == Void.TYPE
            }
            check(callbacks.isNotEmpty()) { "onChange not found" }
            callbacks.forEachIndexed { index, callback ->
                install(callback, "screenshot.observer[$index]") { null }
            }
        }.onFailure { logMissing("screenshot.observer", it) }
    }

    private fun hookWindowFlags() {
        val setFlags = runCatching {
            Window::class.java.getDeclaredMethod(
                "setFlags",
                Int::class.javaPrimitiveType!!,
                Int::class.javaPrimitiveType!!,
            )
        }.getOrNull()
        if (setFlags != null) {
            deoptimize(setFlags)
            install(setFlags, "window.setFlags") { chain ->
                if (ViewerGuard.active) {
                    // setFlags(flags, mask): clear the secure bit from flags.
                    val args = chain.args.toMutableList()
                    args[0] = (args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    chain.proceed(args.toTypedArray())
                } else {
                    chain.proceed()
                }
            }
        }

        val addFlags = runCatching {
            Window::class.java.getDeclaredMethod("addFlags", Int::class.javaPrimitiveType!!)
        }.getOrNull()
        if (addFlags != null) {
            deoptimize(addFlags)
            install(addFlags, "window.addFlags") { chain ->
                if (ViewerGuard.active) {
                    val args = chain.args.toMutableList()
                    args[0] = (args[0] as Int) and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    chain.proceed(args.toTypedArray())
                } else {
                    chain.proceed()
                }
            }
        }
    }

    private fun hookViewer(
        className: String,
        tag: String,
        methods: (Class<*>) -> List<Method>,
    ) {
        val clazz = runCatching { Class.forName(className, false, classLoader) }.getOrNull()
        if (clazz == null) {
            logMissing(tag, null)
            return
        }
        val found = methods(clazz)
        if (found.isEmpty()) {
            logMissing(tag, null)
            return
        }
        for (method in found) {
            install(method, tag) { chain ->
                ViewerGuard.enter()
                try {
                    val result = chain.proceed()
                    clearSecure(chain.thisObject)
                    result
                } finally {
                    ViewerGuard.exit()
                }
            }
        }
    }

    private fun clearSecure(target: Any?) {
        runCatching {
            val dialog = target?.javaClass?.getMethod("getDialog")?.invoke(target) as? Dialog
            dialog?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    // ------------------------------------------------------------------- stories

    private fun installStories(resolved: Map<String, List<MethodRef>>) {
        // The inbox row of stories and notes.
        hookAll(resolved, "stories.gate", "stories.gate") { false }
        hookAll(resolved, "stories.reels_badge", "stories.reels_badge") { false }

        // HushMessenger stops the viewer's advance instead of hiding the page.
        // Returning early from the completion leaves the story on screen.
        hookAll(resolved, "stories.finish", "stories.finish") { null }
    }

    // -------------------------------------------------------------------- engine

    private fun hook(
        resolved: Map<String, List<MethodRef>>,
        key: String,
        tag: String,
        block: (Chain) -> Any?,
    ) {
        val ref = resolved[key]?.firstOrNull() ?: run {
            logMissing(tag, null)
            return
        }
        install(ref.toMethod(classLoader), tag, block)
    }

    /**
     * Hooks a fingerprint only when it resolved to exactly one method.
     *
     * Used where a false positive would change behaviour outside the feature,
     * such as a generic window helper.
     */
    private fun hookUnique(
        resolved: Map<String, List<MethodRef>>,
        key: String,
        tag: String,
        block: (Chain) -> Any?,
    ) {
        val refs = resolved[key].orEmpty()
        when {
            refs.isEmpty() -> logMissing(tag, null)
            refs.size > 1 -> module.log(
                Log.WARN,
                TAG,
                "not installed: $tag (${refs.size} candidates, expected one)",
            )
            else -> install(refs[0].toMethod(classLoader), tag, block)
        }
    }

    private fun hookAll(
        resolved: Map<String, List<MethodRef>>,
        key: String,
        tag: String,
        limit: Int = 8,
        block: (Chain) -> Any?,
    ) {
        val refs = resolved[key].orEmpty()
        if (refs.isEmpty()) {
            logMissing(tag, null)
            return
        }
        refs.take(limit).forEachIndexed { index, ref ->
            install(ref.toMethod(classLoader), "$tag[$index]", block)
        }
    }

    /**
     * Hooks the candidate whose Redex `__redex_internal_original_name` matches.
     *
     * Redex renames synthetic classes but keeps the source name in that static
     * field. It is the same field HushMessenger's patches read, and it tells
     * the "started typing" runnable apart from the "stopped typing" one without
     * ever risking the latter. A lone candidate is accepted only when it has no
     * Redex name field at all (the name was stripped); an ambiguous set or a
     * candidate with a different name is left alone.
     */
    private fun hookByOriginalName(
        resolved: Map<String, List<MethodRef>>,
        key: String,
        tag: String,
        expectedName: String,
        block: (Chain) -> Any?,
    ) {
        val refs = resolved[key].orEmpty()
        if (refs.isEmpty()) {
            logMissing(tag, null)
            return
        }
        val methods = refs.mapNotNull { it.toMethod(classLoader) }
        val matches = methods.filter { redexOriginalName(it) == expectedName }
        val targets = when {
            matches.isNotEmpty() -> matches
            methods.size == 1 && !hasRedexNameField(methods[0]) -> methods
            else -> {
                module.log(
                    Log.WARN,
                    TAG,
                    "not installed: $tag (${methods.size} candidates, " +
                        "none named $expectedName)",
                )
                return
            }
        }
        targets.forEachIndexed { index, method -> install(method, "$tag[$index]", block) }
    }

    private fun hasRedexNameField(method: Method): Boolean = runCatching {
        method.declaringClass.declaredFields.any {
            it.name == "__redex_internal_original_name"
        }
    }.getOrDefault(true)

    private fun redexOriginalName(method: Method): String? = runCatching {
        method.declaringClass
            .getDeclaredField("__redex_internal_original_name")
            .apply { isAccessible = true }
            .get(null) as? String
    }.getOrNull()

    private fun install(method: Method?, tag: String, block: (Chain) -> Any?) {
        if (method == null) {
            logMissing(tag, null)
            return
        }
        runCatching {
            module.hook(method)
                .setPriority(XposedInterface.PRIORITY_DEFAULT)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain -> block(chain) }
        }.onSuccess {
            module.log(Log.INFO, TAG, "hooked $tag -> ${method.declaringClass.name}#${method.name}")
        }.onFailure {
            module.log(Log.ERROR, TAG, "hook failed: $tag", it)
        }
    }

    private fun deoptimize(method: Method) {
        runCatching { module.deoptimize(method) }
            .onFailure { module.log(Log.WARN, TAG, "deoptimize failed: ${method.name}", it) }
    }

    private fun logMissing(tag: String, error: Throwable?) {
        if (error != null) {
            module.log(Log.WARN, TAG, "not installed: $tag", error)
        } else {
            module.log(Log.WARN, TAG, "not installed: $tag (fingerprint not found)")
        }
    }

    companion object {
        private const val TAG = "HushXposed"
        private const val INBOX_IMPRESSION_EVENT = "inbox2_vr"
    }
}
