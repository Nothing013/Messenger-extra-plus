package app.hushmessenger.xposed.hooking

/**
 * Marks the current thread as executing inside a protected media viewer.
 *
 * The Window flag hooks are process-wide, so they must only strip FLAG_SECURE
 * while one of Messenger's view-once viewers is on the stack. A depth counter
 * keeps nested viewer calls safe.
 */
object ViewerGuard {

    private val depth = ThreadLocal.withInitial { 0 }

    val active: Boolean get() = depth.get() > 0

    fun enter() {
        depth.set(depth.get() + 1)
    }

    fun exit() {
        val next = depth.get() - 1
        if (next <= 0) depth.remove() else depth.set(next)
    }
}
