package app.messengerhook.xposed

import android.content.pm.ApplicationInfo
import android.util.Log
import app.messengerhook.xposed.core.FeatureState
import app.messengerhook.xposed.core.Features
import app.messengerhook.xposed.core.RESCAN_KEY
import app.messengerhook.xposed.core.SETTINGS_GROUP
import app.messengerhook.xposed.core.TARGET_PACKAGE
import app.messengerhook.xposed.dex.DescriptorCache
import app.messengerhook.xposed.dex.DexKitEngine
import app.messengerhook.xposed.dex.DexKitLoader
import app.messengerhook.xposed.dex.MethodRef
import app.messengerhook.xposed.hooking.HookInstaller
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Modern Xposed entry point.
 *
 * Lifecycle:
 *  1. onPackageLoaded: read switches, resolve (or load cached) descriptors with
 *     DexKit against the Messenger APK. The classloader is not needed yet.
 *  2. onPackageReady: resolve descriptors to java.lang.reflect.Method on the
 *     now-ready host classloader and install interceptor hooks.
 */
class MessengerHookModule : XposedModule() {

    private val pending = ConcurrentHashMap<String, Map<String, List<MethodRef>>>()

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(
            Log.INFO,
            TAG,
            "loaded in ${param.processName}; framework=$frameworkName " +
                "$frameworkVersionCode api=$apiVersion",
        )
    }

    override fun onPackageLoaded(param: PackageLoadedParam) {
        if (param.packageName != TARGET_PACKAGE) return
        if (!param.isFirstPackage) return

        // onPackageLoaded only exists on Android 10+, so this is an early scan.
        // onPackageReady repeats it if the callback never arrives (Android 8-9).
        prepare(param.applicationInfo)?.let {
            pending[param.packageName] = it
        }
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (param.packageName != TARGET_PACKAGE) return

        val resolved = pending.remove(param.packageName)
            ?: prepare(param.applicationInfo)
            ?: return
        val state = readState()
        if (!state.anyEnabled) return

        runCatching {
            HookInstaller(
                module = this,
                classLoader = param.classLoader,
                state = state,
                dataDir = File(param.applicationInfo.dataDir, "files"),
            ).install(resolved)
        }.onFailure {
            log(Log.ERROR, TAG, "hook installation failed", it)
        }
    }

    /**
     * Reads the switches, then either loads the descriptor cache or runs DexKit.
     * Returns null when nothing should be installed.
     */
    private fun prepare(
        info: ApplicationInfo,
    ): Map<String, List<MethodRef>>? {
        val state = readState()
        if (!state.anyEnabled) {
            log(Log.INFO, TAG, "no features enabled; nothing to resolve")
            return null
        }

        val cacheKey = buildKey(info)
        val needed = neededKeys(state)
        if (needed.isEmpty()) {
            // Nothing to resolve; install the feature hooks that use stable names.
            return emptyMap()
        }

        // RemotePreferences are read-only here, so the descriptor cache lives in
        // Messenger's own files directory. The settings app can only drop a
        // rescan token into the settings group; comparing it against the token
        // stored with the cache is what makes the "rescan" button work.
        val cache = DescriptorCache(
            dataDir = File(info.dataDir, "files"),
            buildKey = cacheKey,
        )
        val rescanToken = runCatching {
            getRemotePreferences(SETTINGS_GROUP).getLong(RESCAN_KEY, 0L)
        }.getOrDefault(0L)

        val entry = cache.read()
        val rescanRequested = entry != null && entry.rescanToken != rescanToken
        val usable = entry != null &&
            !rescanRequested &&
            entry.scanned.containsAll(needed)
        val resolved: Map<String, List<MethodRef>> = when {
            usable -> {
                log(Log.INFO, TAG, "using cached descriptors for $cacheKey")
                entry?.resolved.orEmpty()
            }
            !DexKitLoader.ensure(getModuleApplicationInfo()) -> {
                // The cache may still cover a previous successful scan, and
                // screenshot hooks need no descriptors at all.
                log(Log.ERROR, TAG, "DexKit unavailable; using cached descriptors only")
                return entry?.resolved.orEmpty()
            }
            else -> {
                val fresh = runCatching {
                    DexKitEngine(info.sourceDir).resolve(needed)
                }.getOrElse {
                    log(Log.ERROR, TAG, "DexKit resolution failed", it)
                    null
                }
                if (fresh == null) {
                    // The bridge could not be created. Do not cache an empty
                    // result; keep whatever a previous scan produced.
                    log(Log.ERROR, TAG, "DexKit scan unavailable; keeping cached descriptors")
                    return entry?.resolved.orEmpty()
                }
                // A requested rescan starts from scratch so descriptors for a
                // method that no longer exists cannot linger. A scan that only
                // adds newly enabled features keeps the rest.
                val base = if (rescanRequested) {
                    emptyMap<String, List<MethodRef>>()
                } else {
                    entry?.resolved.orEmpty()
                }
                val merged = base + fresh
                val scanned = (if (rescanRequested) emptySet<String>() else entry?.scanned.orEmpty()) + needed
                cache.write(merged, scanned, rescanToken)
                merged
            }
        }

        log(
            Log.INFO,
            TAG,
            "resolved ${resolved.size} descriptor groups for $cacheKey " +
                "(features=${state.enabledKeys()})",
        )
        return resolved
    }

    private fun readState(): FeatureState = FeatureState(
        runCatching { getRemotePreferences(SETTINGS_GROUP) }.getOrNull(),
    )

    private fun neededKeys(state: FeatureState): Set<String> = buildSet {
        if (state.isOn(Features.READ_RECEIPTS)) add("read_seen")
        if (state.isOn(Features.TYPING)) {
            add("typing_mailbox")
            add("typing_run")
        }
        if (state.isOn(Features.ADS)) {
            addAll(
                listOf(
                    "ads.banner",
                    "ads.inbox_promo",
                    "ads.chat_promo",
                    "ads.entry",
                    "ads.tracker",
                    "ads.logger",
                ),
            )
        }
        if (state.isOn(Features.META_AI)) {
            addAll(
                listOf(
                    "ai.tab",
                    "ai.muse",
                    "ai.toolbar",
                    "ai.stickers",
                    "ai.summary",
                    "ai.mentions",
                ),
            )
        }
        if (state.isOn(Features.STORIES)) {
            addAll(listOf("stories.gate", "stories.reels_badge", "stories.finish"))
        }
        if (state.isOn(Features.SCREENSHOTS)) {
            // The viewer methods themselves are found by stable class name; only
            // the window-flag helper and Android 14's capture callback need DEX
            // matching.
            addAll(listOf("screenshots.window_helper", "screenshots.capture"))
        }
        if (state.isOn(Features.KEEP_UNSENT)) {
            addAll(
                listOf(
                    "unsent.keep",
                    "unsent.recall",
                    "unsent.id",
                    "unsent.state",
                    "unsent.text",
                ),
            )
        }
    }

    /**
     * Identifies the installed Messenger build for the descriptor cache.
     *
     * [ApplicationInfo] carries no version code; that lives on [android.content.pm.PackageInfo],
     * which would need a PackageManager this early in process startup. The base APK's
     * size and timestamp change on every install, and Android places each install in
     * a fresh directory, so the pair is a stable per-build key that needs no framework
     * access. A failed stat ("0-0") simply disables cache reuse.
     */
    private fun buildKey(info: ApplicationInfo): String {
        val apk = File(info.sourceDir)
        return "${apk.length()}-${apk.lastModified()}"
    }

    companion object {
        private const val TAG = "MessengerHook"
    }
}
