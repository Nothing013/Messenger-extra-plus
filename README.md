# HushMessenger Xposed

A standalone Modern Xposed module for Facebook Messenger (`com.facebook.orca`) that ports the
Tier 1 controls from the HushMessenger Morphe patch set from a patched-APK build to a runtime
hook.

It uses **only**:

- `io.github.libxposed:api` — the official Modern Xposed API (`compileOnly`)
- `io.github.libxposed:service` — settings-to-framework bridge (`implementation`)
- `org.luckypray:dexkit` — runtime DEX matching for obfuscated Messenger methods

There is no YukiHookAPI, no legacy `XposedBridge`, and no `de.robv.android.xposed` dependency.

---

## API level note

`io.github.libxposed:api:100` was never published to Maven Central. The first published modern
artifact is **101.0.0**, followed by **101.0.1** and **102.0.0**. This module pins:

| Artifact | Version |
| --- | --- |
| `io.github.libxposed:api` | `101.0.1` |
| `io.github.libxposed:service` | `101.0.0` |

`module.prop` therefore declares `minApiVersion=101` / `targetApiVersion=101`, which is the
modern API generation LSPosed and Vector actually implement. Everything in this project is
written against the modern API surface, not the legacy one.

---

## Requirements

- Android 8.0+ (`minSdk 26`), `compileSdk`/`targetSdk` 37 (Android 17)
- An Xposed framework with Modern Xposed API support
  (LSPosed 1.9.x+ or Vector; legacy-only frameworks are not supported)
- Messenger `588.0.0.20.98` (versionCode `347610355`) is the tested build. Other versions work on
  a best-effort basis: every fingerprint is matched against the installed APK, and a miss simply
  leaves that feature off.

---

## Install

1. Build or download `app-debug.apk`.
2. Install it and open **HushMessenger Xposed** once.
3. In LSPosed/Vector, enable the module and tick **Messenger** (`com.facebook.orca`).
4. Force-stop Messenger (or reboot).
5. Open the module, turn on the features you want.
6. Force-stop Messenger again. The hook reads the switches when Messenger loads.

The settings app can be open while Messenger runs; the hook intentionally does **not** hot-swap
hooks mid-process. This keeps a call from seeing a hook set change underneath it.

---

## Features

All features default to **off**.

The first six switches are the original Tier 1 port. **Keep unsent messages** is
the first Tier 2 control; it ships on its own so it can be tested without the
rest of Batch C.

| Switch | What it does | Fingerprint / mechanism |
| --- | --- | --- |
| Hide read receipts | Skips Messenger's static Seen broadcast. The chat still updates locally; only the send is stopped. | Static `(FbUserSession, <handler>, String)V` naming `MARK_THREAD_AS_SEEN_JOB` |
| Hide typing indicator | Encrypted chats: forces the `(String, boolean)` mailbox call to `false`. Normal chats: returns early from the send-active-state Runnable. | `setTypingIndicatorForThreadWithThreadIdentifier`, plus classes that publish the `/t_st` MSYS indicator with a plain `run()V` (the send/cancel pair is separated by the Redex original name) |
| Allow screenshots | Strips `FLAG_SECURE` only while an Ephemeral/Quicksnap viewer is on the stack, skips the media-viewer window helper and Android 14's screen-capture callback, and stops the screenshot notice observer. | Hook `Window.setFlags`/`addFlags` with a thread-local viewer guard; DexKit: instance `(Window)V` with a `0x2000` literal that calls `addFlags`, plus `Activity$ScreenCaptureCallback.onScreenCaptured`; `ScreenshotContentObserver.onChange` |
| Hide ads and promotions | Disables the business-chat ad banner and the inbox/chat Quick Promotion gates, skips the click-to-message ad deep link, and drops the inbox ad impression at the shared logger. | `CTMAdsThreadViewBannerImplementation`, `QuickpromotionThreadlist*`, `QuickpromotionThreadview*`, `ad_id`, `inbox2_vr`; the logger is hooked once and checks the event object's String fields at runtime |
| Hide Meta AI | Disables the Meta AI/MUSE tab, AI toolbar button and AI sticker tab gates, returns `false` from the summary eligibility check, and skips the Meta AI @-mention dispatchers. | `MetaAiTabContentImplementation`, `MuseTabContentImplementation`, `AiHomeButtonKillSwitch`, `KeyboardlsGeneratedtabCoreKillSwitch`, `AiSummaryFloatingPillEventHandler` (class-level: the pill and the check live in different methods), `MentionAiRowSupplier` |
| Hide Stories and Reels | Disables the stories/notes inbox row and the Reels badge, and keeps a finished story on screen instead of advancing. | `FriendsInboxUnitKillSwitch`, `ReelsBadgeKillSwitch`, `story_playback_finished` |
| Keep unsent messages | Holds back the revoke dispatcher, records the message id, keeps the row out of its unsent state, prefixes kept text with `[unsent] `, and keeps the notification for unsends that never reach the chat. | Instance `(Intent, FbUserSession, String, Map)V` naming `ACTION_REVOKE_MESSAGE`; instance `(FbUserSession, String, String, String)Z` naming `NotificationRecaller`; the row wrapper's `text=`/`message_id=`/`is_unsent=` getters, paired from DexKit's ordered opcode, invoke and string lists |

### Verification

All fingerprints were checked against the installed `588.0.0.20.98` DEX with an independent
parser. Every query resolves to exactly one method (or, for gates with two anchors, one per
anchor); the typing query resolves to the send/cancel pair and the Redex name selects the send
Runnable. The project itself has not been compiled in this environment because no JDK/Android SDK
is available; CI performs the first real build.

### Deliberate limitations

- **Read receipts are suppress-only.** The Morphe patch can hold the Seen event and replay it
  when you reply within five seconds. That needs a field resolver for Messenger's thread key and
  a stateful replay path; v1 does not do it. Turn the switch off to send Seen normally.
- **No mid-method skipping.** Xposed cannot skip a range of instructions. Where the original
  patch skipped one call in the middle of a method, this module either returns early from the
  whole method or hooks the callee. The ad impression is dropped at the shared logger, but only
  when its event object carries `inbox2_vr`; every other analytics event passes through. The
  logger must also be the tracker's single non-framework one-argument `void` callee, otherwise
  the tracker is left untouched.
- **Typing relies on Redex names for the send Runnable.** The engine finds both typing
  Runnables by the `/t_st` protocol string, but only the one whose
  `__redex_internal_original_name` is `ConversationTypingContext$sendActiveStateRunnable$1` is
  hooked. If Redex strips that field and more than one candidate remains, the normal-chat path is
  left alone (the encrypted-chat mailbox rewrite still applies).
- **Screenshots are viewer-scoped.** `FLAG_SECURE` is removed only while a protected viewer is
  running, not process-wide. This allows capturing view-once media without weakening every
  Messenger window.
- **Fingerprints can miss.** Anchors come from the HushMessenger sources, and some releases move
  gate names into Redex string tables. A missed anchor logs `not installed: <key>` and leaves
  stock behaviour in place.
- **Tab filtering is not implemented.** Stories is an inbox unit, not a bottom-bar tab. The
  People/Notifications/AI tab controls are outside Tier 1 and are not part of this module.

---

## How it works

```
META-INF/xposed/java_init.list  ->  app.hushmessenger.xposed.HushModule
                                      |
onPackageLoaded (com.facebook.orca)   |  read switches from RemotePreferences
                                      |  DexKit scan (or cached descriptors)
                                      v
onPackageReady (classloader ready) -> HookInstaller
                                      |
                                      v
                          module.hook(method).intercept { chain -> ... }
```

### Descriptor cache

DexKit matches against Messenger's APK path and produces classloader-neutral descriptors:

```
binary.ClassName|methodName|param1,param2|returnType
```

They are stored in `files/hush_xposed/descriptors-<buildKey>.txt` inside Messenger's own data
directory, together with the set of queries already attempted and the rescan token the hook last
saw. `buildKey` is the base APK's size plus timestamp (`ApplicationInfo` has no version code),
which changes on every Messenger install. The hook runs as Messenger, so this is writable;
RemotePreferences are **read-only** in a hooked app and therefore cannot hold a cache built at
runtime. The scan runs once per Messenger build, and files for older builds are pruned on the next
save.

The settings screen's **Rescan Messenger on next start** button writes a new `rescan_at` token
into `hush_settings`. The hook compares it with the token stored beside the cache, rescans once
from scratch when it differs, and records the token it used.

The scan runs synchronously when Messenger starts, so the first start after a Messenger update
can take a few seconds longer. After the cache is written, later starts (and Messenger's other
processes, which share the same file) read the cache without touching DexKit.

### Settings transport

The settings app calls `XposedService.getRemotePreferences("hush_settings")` through the provider
that `io.github.libxposed:service` merges into the manifest. The hook calls
`XposedModule.getRemotePreferences("hush_settings")` in Messenger's process. Both sides therefore
read the same framework-backed map — no shared files, no `WorldReadablePreferences`, no
broadcast permissions. Only switches and the rescan token live there; the descriptor cache does
not.

---

## Build

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Release builds are minified and signed with the debug key so CI artifacts are installable
without extra secrets:

```bash
./gradlew assembleRelease
```

GitHub Actions builds and uploads **both** the debug and release APKs on every push
(`hushmessenger-xposed-debug`, `hushmessenger-xposed-release`). Pushing a `v*` tag additionally
attaches the release APK to the GitHub release.

---

## Module metadata

Modern metadata lives in `app/src/main/resources/META-INF/xposed/`:

- `module.prop` — `minApiVersion=101`, `targetApiVersion=101`, `staticScope=true`,
  `exceptionMode=protective`
- `scope.list` — `com.facebook.orca`
- `java_init.list` — `app.hushmessenger.xposed.HushModule`

The manifest also keeps the legacy `xposedmodule` / `xposedminversion` metadata so LSPosed and
Vector list the module immediately, even before they read the modern files.

---

## Logs

Everything is logged under the tag `HushXposed`:

```bash
adb logcat -s HushXposed
```

Each install logs `hooked <feature> -> <class>#<method>`, and each miss logs
`not installed: <feature>`. Resolution failures include the DexKit query key.

---

## License

Personal-use project. The fingerprint knowledge comes from HushMessenger and the
De-Vanced-derived anchors documented in its sources.
