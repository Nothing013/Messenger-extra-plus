# Roadmap

The module grows one feature at a time. The goal is not just "add hooks" but
"add hooks that cannot take the rest of the app down when a fingerprint moves
or a hook returns the wrong shape".

Target: **Android 17 / API 37** (`compileSdk = 37`, `compileSdkMinor = 0`,
`targetSdk = 37`).

---

## Safety model

These layers already exist; new features must use them.

1. **Per-feature install isolation.** `HookInstaller.install()` wraps every
   feature in `isolated(...)`. An exception in one installer is logged as
   `feature '<name>' failed to install; continuing` and the rest still install.
2. **Per-query DexKit isolation.** Every fingerprint runs through `runCatching`,
   and the whole scan is wrapped again in `MessengerHookModule.prepare`. A missing or
   broken query logs `query <key> failed` and resolves nothing for that key.
3. **Protective hook bodies.** Every hook is registered with
   `ExceptionMode.PROTECTIVE`: a throw before `chain.proceed()` behaves as if
   the hook did not exist; a throw after it returns the proceeded result.
4. **Explicit miss.** An unresolved fingerprint logs
   `not installed: <tag> (fingerprint not found)` and leaves Messenger stock.
5. **Opt-in switches.** Features default to off and are read once when Messenger
   starts. Restart Messenger after changing them.

If a feature misbehaves, turn only that switch off, force-stop Messenger, and
check `adb logcat -s MessengerHook`. No need to uninstall the module.

---

## Adding a feature

1. Add a key and a `Feature(...)` entry to `core/Features.kt` (default off).
2. Add the query to `dex/DexKitEngine.kt` under a new key, and add the key to
   `MessengerHookModule.neededKeys()` when its switch is on.
3. Verify the fingerprint against the installed Messenger APK before hooking it.
4. Add `installX()` to `hooking/HookInstaller.kt` and one `isolated(...)` line.
5. Build, enable **only** the new switch, force-stop Messenger, test.
6. Commit and stop. Enable the next switch in a separate change.

This keeps the blast radius of a bad guess to one switch and one log line.

---

## Tier 2 candidates

From the upstream patch catalogue, grouped by risk. Names are the patch
names; fingerprints still need to be resolved against the current Messenger
build before each one ships.

### Batch A — inbox and UI gates (low risk)

Usually one boolean gate per patch, same shape as the existing ads/Meta AI
gates: a method that names a plugin implementation and returns `false`.

- Hide Notifications tab
- Hide People tab
- Hide Stories tab
- Hide tab badges
- Hide inbox tabs (All / Unread / Groups)
- Hide People You May Know
- Hide friend request cards
- Hide the Instants button
- Hide the recent photo card
- Hide Facebook shortcuts

### Batch B — telemetry and network (medium risk)

Call-site hooks. Test each one alone and watch for Messenger retries or UI that
waits on the suppressed job.

- Stop analytics uploads
- Stop ad attribution uploads
- Stop app list and device ID sharing
- Stop nearby scans
- Stop live connection logging
- View stories anonymously

### Batch C — message behaviour (higher risk)

These change message state, not just visibility. Expect more testing per
feature and a longer observation window before enabling the next one.

- **Keep unsent messages — done.** `unsent.keep`, `unsent.recall`,
  `unsent.id`, `unsent.state` and `unsent.text`; ids persist in
  `files/messenger_hook/kept_unsent.txt` on Messenger's data directory.
- Ignore the swipe up for disappearing messages
- Hide Your story when sharing
- Hide Mark as read
- Keep a message log

The notification clear path (`unsent_clear`) is intentionally left for a later
increment: the chat row already stays, and the clear site is a mid-method
register injection rather than a whole-method hook.

### Out of scope for now

Build-time patches (clone install, re-signed build fixes), media transcoding,
call recording, custom fonts/sounds/icons, and Auto reply. They need file
pickers, permissions or build-variant work and do not fit the current
hook-only design.
