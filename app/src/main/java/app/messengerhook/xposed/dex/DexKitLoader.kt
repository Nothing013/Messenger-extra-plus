package app.messengerhook.xposed.dex

import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File

/**
 * Loads DexKit's native bridge.
 *
 * DexKit 2.3.0's libdexkit.so only needs libc, libm, libz, libdl and liblog:
 * libc++ is linked statically, so no libc++_shared.so preload is required.
 *
 * `jniLibs.useLegacyPackaging = true` in app/build.gradle.kts extracts the
 * module's native libraries at install time, so they can be loaded by absolute
 * path from the module's own nativeLibraryDir. That fallback is required
 * because this module runs inside Messenger, whose process library path does
 * not include our APK's libs.
 */
object DexKitLoader {

    private const val TAG = "MessengerHook"

    @Volatile
    private var loaded = false

    @Synchronized
    fun ensure(moduleInfo: ApplicationInfo?): Boolean {
        if (loaded) return true
        if (load(moduleInfo, "dexkit", "libdexkit.so")) {
            loaded = true
            return true
        }
        Log.e(TAG, "DexKit native library could not be loaded")
        return false
    }

    /**
     * Tries the classloader-aware `System.loadLibrary` first, then the module's
     * own nativeLibraryDir by absolute path.
     */
    private fun load(
        moduleInfo: ApplicationInfo?,
        library: String,
        fileName: String,
    ): Boolean {
        runCatching { System.loadLibrary(library) }
            .onSuccess { return true }
            .onFailure { Log.w(TAG, "System.loadLibrary($library) failed: ${it.message}") }

        val dir = moduleInfo?.nativeLibraryDir
        if (!dir.isNullOrBlank()) {
            val file = File(dir, fileName)
            if (file.isFile) {
                runCatching { System.load(file.absolutePath) }
                    .onSuccess { return true }
                    .onFailure { Log.w(TAG, "System.load(${file.absolutePath}) failed", it) }
            }
        }
        return false
    }
}
