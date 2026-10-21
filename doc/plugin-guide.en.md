# Ponko Plugin Developer's Guide

For anyone writing a plugin — most likely me, three months from now.

This guide covers **how**. For why things are designed this way and which traps
were hit along the way, see [`../log/`](../log/) (Chinese only).

[中文](plugin-guide.md)

---

## 1. What a plugin is

A **standalone APK** with its own package name, its own process, its own `.so`
and models. It talks to Ponko over AIDL; Ponko knows the interface, not the
implementation.

```
┌─ Ponko (com.litertchat.app) ─┐        ┌─ plugin (com.litertchat.plugin.xxx) ─┐
│                              │        │                                      │
│  PluginSession               │ bind   │  XxxPluginService : PonkoPluginService
│   └ PluginConnection ────────┼───────►│    ├ loadEngineBlocking()            │
│        │                     │ AIDL   │    ├ synthesizeBlocking()            │
│        ◄─────────────────────┼────────│    └ closeEngineBlocking()           │
│        onStage / onResult    │callback│                                      │
└──────────────────────────────┘        └──────────────────────────────────────┘
```

**Why it has to be an APK**: Android 10+ forbids `dlopen` of executables from an
app's own directory. Only `.so` files that the system extracts into
`nativeLibraryDir` at install time can be loaded — and that directory only ever
receives what ships inside an APK. So "drop a `.so` on the phone and let Ponko
load it" is blocked at the OS level, with no workaround.

### Division of labour

| Who | Owns |
|---|---|
| Plugin | **What it can do**: load models, run inference, describe a config form |
| Host | **How it's shown**: colours, widget styling, button placement, light/dark |

Almost every design decision downstream follows from this: a plugin **writes no
layout code**, and cannot read the host's theme.

---

## 2. A minimal plugin: five files

Copy `plugins/echo` (on the `Ponko-EX_test` branch) and change three things.

```
plugins/myplugin/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    ├── java/com/litertchat/plugin/myplugin/
    │   ├── MyPluginActivator.kt     headless activator (5 lines)
    │   └── MyPluginService.kt       all the logic lives here
    └── res/values/strings.xml
```

### build.gradle.kts

```kotlin
android {
    namespace = "com.litertchat.plugin.myplugin"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.litertchat.plugin.myplugin"   // must differ from the host
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // This is the only supported way — project(":ponko-plugin-sdk") does not exist here
    implementation("com.litertchat.ponko:plugin-sdk:1.0.0")
}
```

> Cross-project `project(...)` points at a path that doesn't exist in this build.
> The host must publish the SDK into the local maven repo first
> (`publishReleasePublicationToLocalRepoRepository`).

### Register it in settings.gradle.kts

```kotlin
include(":plugins:echo")          // add one line per module
```

---

## 3. Manifest: six things, and any one missing breaks the connection

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- 1) foreground service + wake lock -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- 2) lets the plugin look up the host (to check whether it's installed) -->
    <queries>
        <package android:name="com.litertchat.app" />
    </queries>

    <application android:allowBackup="false" ...>

        <!-- 3) headless activator — see §4 -->
        <activity
            android:name=".MyPluginActivator"
            android:exported="true"
            android:excludeFromRecents="true"
            android:noHistory="true"
            android:taskAffinity=""
            android:theme="@android:style/Theme.NoDisplay">
            <intent-filter>
                <action android:name="com.litertchat.ponko.ACTIVATOR" />
            </intent-filter>
        </activity>

        <!-- 4/5/6) the plugin service -->
        <service
            android:name=".MyPluginService"
            android:exported="true"
            android:foregroundServiceType="specialUse"
            android:permission="com.litertchat.ponko.permission.BIND_PLUGIN">
            <intent-filter>
                <action android:name="com.litertchat.ponko.PLUGIN" />   <!-- 4) discovery -->
            </intent-filter>
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Runs local speech synthesis requested by Ponko" />  <!-- 5 -->
        </service>
    </application>
</manifest>
```

| # | Item | What breaks without it |
|---|---|---|
| 1 | Foreground service / WAKE_LOCK permissions | Long inference gets killed, or `startForeground` throws |
| 2 | `<queries>` containing the host package | Plugin can't see the host (not fatal, but recommended) |
| 3 | The activator | **A freshly installed plugin can never connect**; `bindService` silently returns false |
| 4 | `intent-filter` + ACTION_PLUGIN | The host never sees you; the plugin isn't even listed |
| 5 | `foregroundServiceType` + subtype property | Android 14+ refuses to start the foreground service |
| 6 | `android:permission` | Anyone can bind — or, if you reverse it, you block yourself |

### Item 6 is easy to get backwards

`android:permission` on a `<service>` constrains the **caller**, not the plugin.

- Placing it on the plugin's `<service>` means "**whoever binds me must hold this permission**"
- So the **host** declares `<uses-permission>`; the **plugin does not**
- The host also declares `<permission protectionLevel="signature">`, meaning only
  an APK signed with the same key can hold it

Net effect: only same-key APKs can bind; impostors are stopped by the OS itself.

---

## 4. Activation: why there must be an Activity, yet no icon on the launcher

A newly installed package sits in the system's **stopped** state. In that state,
another app's `bindService` to its service returns **false** — no exception, no
error, just an obscure system log line.

There is exactly **one** way out: the package's own Activity must be started once.

Things that are confirmed **not** to work (don't burn time on them): sending a
broadcast, `startService`, `pm enable`, reinstalling the same APK. Since Android 15
the official position is explicit: only a direct or indirect user action clears it.

### The approach: a headless activator

```kotlin
class MyPluginActivator : PonkoActivatorActivity()
```

That's the whole file. The base class checks whether this launch was
"activation only", finishes immediately, and draws nothing.

Combined with the five attributes in the manifest:

- **No launcher icon** (there is no `CATEGORY_LAUNCHER`)
- When the user taps "Enable", the host starts it automatically — the user sees a
  flicker, comes back, and it's connected
- Like a system component, it's only visible under Settings → Apps

> Key insight: clearing the stopped flag only requires *some component to be
> started*; it does **not** require that component to have a launcher entry.
> Those two are frequently conflated.

The user can also tap "Activate" on the card manually — same action, used as a
remedy after a force-stop or when a particular ROM blocks automatic activation.

---

## 5. AIDL quick reference (API v2)

### Host → plugin

| Method | Sync? | Notes |
|---|---|---|
| `getApiVersion()` | sync | Returns `PluginConst.API_VERSION`; the host validates it before going further |
| `getManifest()` | sync | Self-description |
| `registerCallback(cb)` | sync | First thing the host does after binding; last call wins |
| `loadEngine(models, config)` | oneway | Idempotent (ignored while loading / loaded). The host does send a real `config.threads` (see §6.1) |
| `closeEngine()` | oneway | Frees model memory; during inference it cancels first |
| `isEngineReady()` | sync | **Must be O(1)**, a volatile read |
| `getStatus()` | sync | **Must be O(1)**, returns `EngineStatus` |
| `synthesize(req)` | oneway | One job at a time; returns `BUSY` immediately instead of queueing |
| `cancel()` | oneway | Plugin must answer with `onError(CANCELLED)` |
| `getSettingsSchema()` | sync | Returns the form; **must be fast**, no I/O |
| `setSetting(key, value)` | oneway | User changed a field |
| `invokeAction(key)` | oneway | User tapped an `ACTION` item |

> Only three and a half methods are synchronous (version, ready, status, form).
> They run on the **host's Binder thread**. Locking or waiting on a worker occupies
> one of the host's 16 Binder threads, and every other IPC queues behind it.
> So implementations read cached fields only.

### Plugin → host

| Method | When |
|---|---|
| `onEngineLoaded(info)` | Loaded; reports sample rate / channels / bytes |
| `onEngineError(err)` | Load failed (OOM, corrupt file, wrong ABI) |
| `onStage(stage, label)` | Stage change; `label` is human-readable, shown verbatim |
| `onAudioChunk(pcm, rate, ch, final)` | Only when `streamAudio=true`; ≤ 64 KB per chunk |
| `onResult(bundle)` | Final result, **exactly once** |
| `onError(err)` | Error, **exactly once**; cancellation also comes through here |
| `onSchemaChanged()` | Form data changed — please repaint |

**`oneway` has no backpressure.** A fast producer and a slow consumer queue up on
the host side; in the extreme this exhausts the 1 MB Binder budget and drags down
every other IPC. That's why the base class throttles `onStage` to 500 ms — don't
bypass it and spam.

---

## 6. What the base class does for you, and what's left

`PonkoPluginService` already implements:

- **Threading**: every `oneway` call arrives on a Binder thread and is immediately
  handed to a **single-threaded worker**. Inference never runs on a Binder thread
  (16 per process; occupying them stalls other IPC)
- **Foreground service**: `startForeground` happens in `onBind` (see §12)
- **Wake lock**: `PARTIAL_WAKE_LOCK` released on all exits, with a 30-minute cap per
  acquisition and **automatic renewal every 10 minutes** — so long jobs such as
  downloading a 3.5 GB model don't lose the lock halfway through
- **State machine**: `UNLOADED / LOADING / READY / RUNNING`; `isEngineReady()` is a
  volatile read
- **Cancellation**: `cancel()` sets a flag; call `throwIfCancelled()` in long loops
- **Deduplication**: `onResult` / `onError` fire exactly once
- **Error mapping**: `PluginException` → error code; anything else → `PLUGIN_INTERNAL`

You implement four methods:

```kotlin
class MyPluginService : PonkoPluginService() {

    override fun createManifest(): PluginManifest = PluginManifest(
        pluginId = "myplugin",
        displayName = "My Plugin",
        versionName = "1.0.0",
        author = "…",
        capabilities = listOf(Capability.TTS),
        modelFamilies = listOf("my-model"),
        needsHostModels = false,
        minSdkHint = null,
    )

    override fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo {
        // May block for tens of seconds; may poll progress in a while loop.
        // modelBytes is for display only — fill 0 if there is no model file.
    }

    override fun closeEngineBlocking() {
        // Idempotent. Free native handles, close files.
    }

    override fun synthesizeBlocking(req: SynthesisRequest, out: Emitter): ResultBundle {
        out.stage(Stage.TEXT_ENCODE, "Analysing text")
        // …call out.throwIfCancelled() periodically in long loops
        out.stage(Stage.DECODE, "3.2 s generated")
        // Hand the audio back: FD for large results, inline for small ones
    }
}
```

`createManifest()` is called on a Binder thread — **keep it cheap and pure**.

### Capabilities describe a promise, not a behaviour

| Constant | Meaning | How the host uses it |
|---|---|---|
| `TTS` | text → audio | Selected by the "speak the answer" route |
| `ASR` | audio → text | reserved |
| `IMG` | image generation | reserved |
| `TEST` | test / sample plugin | Excluded from speech routing, but can still be previewed. **Whether the engine loads on connect is decided separately by `AUTOLOAD_ENGINE`** |

**Sample plugins must declare `TEST`, never `TTS`.** They emit fixed tones, not
speech; declaring `TTS` means the host will pick them for real read-aloud and the
user hears an inexplicable arpeggio.

### 6.1 `AUTOLOAD_ENGINE`: load timing is your call

**Nothing loads automatically by default.** The engine is loaded when the user taps
"Load engine" or "Preview".

That default is deliberate, because the cost of loading differs by orders of
magnitude:

| Plugin type | Load time | Memory |
|---|---|---|
| Toy / pure algorithm (Echo) | under a second | 0 |
| Real model (VoxCPM2) | tens of seconds to a minute | several GB |

The host **cannot guess** which one you are. It used to call `loadEngine`
unconditionally on connect, which meant "user switches to the plugin tab to glance
at the connection state, and several GB start pouring in" — an action they never
requested and cannot decline short of not opening the page. It also evicts the
host's own chat / drawing models and can trigger OOM.

So:

```kotlin
capabilities = listOf(Capability.TTS, Capability.AUTOLOAD_ENGINE)  // load on connect
capabilities = listOf(Capability.TTS)                              // user loads it (recommended for real models)
```

**When to declare it:**

- Loading is essentially free (tens of milliseconds, no memory) → declare it. The
  plugin works the moment it's enabled, minus one tap
- Loading takes seconds, or hundreds of MB → **don't**. Let the user decide when to
  pay that

The UI differs between the two:

| | Declared | Not declared |
|---|---|---|
| After connecting | starts loading right away | does nothing; a "Load engine" button appears |
| User taps "Preview" | synthesises directly | loads first, then synthesises (no manual tap needed) |

In other words **not declaring doesn't make the plugin unusable** — it just hands
the "when do I pay" decision back to the user.

> ⚠ Don't declare it just to feel faster. Being slow isn't the problem; spending the
> user's memory without asking is. This matches the host's own model page, where
> loading is also a manual button.

### 6.2 `config.threads` now arrives with a real value

The plugin page has a "inference threads" setting, and **the host passes it down
when it binds the plugin and loads the engine**. The default is `cores - 1`,
clamped to 1–6; the user can pick auto / 2 / 4 / 6.

So `loadEngineBlocking` should **prefer `config.threads`**, falling back to your own
default only when it is `<= 0`:

```kotlin
override fun loadEngineBlocking(models: ModelBundle?, config: EngineConfig): EngineInfo {
    val threads = if (config.threads > 0) config.threads
                  else minOf(4, Runtime.getRuntime().availableProcessors())
    // …initialise the runtime with `threads`
}
```

The host used to always send `0`, so plugins could only guess. Ignoring the real
value now means the user changes the setting and sees no difference — while the UI
says "takes effect on next engine load". That inconsistency is hard to explain.

> A change does **not** reload the engine automatically (that would throw away the
> model the user is already waiting on). The new value applies on the next
> `loadEngine` call, which is exactly what the host UI says.

---

## 7. Engine lifecycle

```
UNLOADED ──loadEngine──► LOADING ──ok──► READY ──synthesize──► RUNNING
    ▲                       │              ▲                      │
    └────── failure ◄───────┘              └──── done ─────────────┘
    └────────────────── closeEngine / onDestroy ───────────────────┘
```

Three rules:

1. **`loadEngine` is idempotent.** When it's called is decided by §6.1: plugins that
   declare `AUTOLOAD_ENGINE` get one call on connect; others wait for the user to tap
   "Load engine" or "Preview". Repeat calls are free.
2. **After a job the state returns to `READY`, not `UNLOADED`** — unless the engine
   genuinely failed to load. (An early version keyed off "model size > 0", so a
   model-less plugin was marked unloaded right after its first synthesis and the
   second preview reported "engine not loaded". See `log/`.)
3. **`closeEngine` must be idempotent.** The host calls it when the user taps
   "Unload engine", on disable, on page
   destroy, and when the process is reclaimed.

`modelBytes` is **display-only** and plays no part in state transitions. Filling 0
for a model-less plugin is perfectly normal.

---

## 8. Performing a synthesis

### What the request actually carries

Since Ponko 2.0.0 the plugin page has a "preview parameters" block that really
fills these fields in — they are no longer "declared but never used":

| Field | Filled by | Notes |
|---|---|---|
| `text` | user input | **no length cap**, multi-line. The host only shows a time estimate beside it; it never blocks or truncates |
| `mode` | chosen in the UI | `0` plain / `1` clone / `2` continuation |
| `referenceAudio` | host opens the user's file | **an already-open FD**; non-null only when `mode≥1` |
| `referenceTranscript` | user input | filled for `mode=2`; may be empty for `mode=1` |
| `seed` | user input | `0` = random |
| `voiceDescription` | not exposed yet | always an empty string; keep concatenating as usual |
| `cfg` / `timesteps` / `maxSteps` | **not filled** | stay `-1 / 0 / 0`, i.e. "use your defaults" |

`cfg` / `timesteps` are deliberately not exposed: sensible ranges depend entirely on
the model (VoxCPM2 is cfg≈2.0, steps 10–20), so the host cannot pick defaults. Those
belong in the plugin's **own** `UiSchema`; the host stays out of it.

So plugins must tolerate missing values: `<= 0`, `< 0`, and empty strings all mean
"use my default". The host guarantees exactly one thing — when `mode≥1`,
`referenceAudio` is non-null (the UI blocks it otherwise).

### `text` has no length cap, so length is your problem

This matters, because it decides whether you should impose your own limit.

**The host does not limit, truncate, or interrupt.** If the user wants to synthesise
a 5000-character article, the UI lets them — how long they wait is their judgement,
and the host merely shows a rough time estimate beside the field.

Why no cap: **UI is UI, inference is inference.** The host neither knows your RTF nor
has any standing to decide what counts as "too long" for every plugin. A global cap
would rope in lightweight models (Piper and friends) too — 40 characters is pointless
for them. (The host did hard-cap at 40 for a while. That was wrong and has been
removed.)

So what should a plugin do:

| Situation | What to do |
|---|---|
| Can handle any length | Nothing. This is the normal case |
| Has a hard limit (context window, VRAM) | Check it yourself and return `PluginException.invalidRequest("text exceeds N chars, please split")` |
| Length changes internal strategy | Branch on `req.text.length` yourself (smaller batches for long text) |

**Don't count on the host blocking first.** It used to; that was a mistake.

### Two file descriptors, two owners

The `referenceAudio` FD belongs to the **host**. Binder dups it when passing, so the
one you hold is an independent handle:

```kotlin
val fd = req.referenceAudio ?: throw PluginException.invalidRequest("clone mode needs reference audio")
val tmp = File(cacheDir, "ref.wav")
// Copy into your own directory, then **close your copy**
ParcelFileDescriptor.AutoCloseInputStream(fd).use { input ->
    tmp.outputStream().use { input.copyTo(it) }
}
// From here on use your own tmp path: decode, resample to 16 kHz, feed the model
```

- **Don't** hold on to that FD as model input — it points at a user document
  (possibly on cloud storage) and may vanish at any time
- **Do** close your copy, or you leak a handle
- The format is 16-bit PCM WAV by convention, but the user may pick an mp3 —
  decoding to 16 kHz mono is **your** job (the host only hands you the file)

### Progress

```kotlin
out.stage(Stage.TEXT_ENCODE, "Analysing text (${req.text.length} chars)")
out.stage(Stage.DECODE, "${"%.1f".format(seconds)} s generated")
out.stage(Stage.AUDIO_DECODE, "Packing WAV")
out.stage(Stage.DONE, "Done")
```

`stage()` throttles itself to 500 ms. `label` is shown **verbatim**, so write
human language, not `"decoding step 37"`.

> On a real model, `DECODE` accounts for over 80% of the runtime (1–2 minutes).
> You **must** emit "N seconds generated" every 500 ms, or the user will assume a
> hang and force-kill the process.

### Cancellation

The plugin page has a "Stop" button, usable at any point during generation. All the
plugin side has to do is this:

```kotlin
for (i in 0 until n) {
    out.throwIfCancelled()      // throws; the base class reports CANCELLED
    // …one step
}
```

**You must check periodically, and often enough.** Skipping the check doesn't just
make cancellation slow — the host sits on "stopping…" forever, the Preview button
stays greyed out, and the user has to leave the page and come back. Check points that
are too sparse (say, once per finished note) are indistinguishable from no support.

> **Make sure `onError(CANCELLED)` really goes out.** The base class emits it when
> `throwIfCancelled()` throws, so using `throwIfCancelled` is enough; if you return
> early some other way, still let the base class run its cleanup — the host relies on
> that callback to re-enable its buttons.

### Getting audio back: two paths

| Path | Condition | When to use |
|---|---|---|
| `ResultBundle.inlineAudio` | result < 256 KB | short clips, tests |
| `ResultBundle.audio` (`ParcelFileDescriptor`) | any size | **the normal choice** |

```kotlin
val dir = File(cacheDir, "out").apply { mkdirs() }
val f = File(dir, "speech.wav")
f.writeBytes(wav)

return ResultBundle(
    requestId = req.requestId,
    sampleRate = 48000,
    channelCount = 1,
    durationMs = …,
    elapsedMs = …,
    audioFormat = PluginConst.FMT_WAV,      // or FMT_PCM16
    audio = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY),
    inlineAudio = null,
    debugInfo = "…",
)
```

- **Don't pass a path**: the plugin can't read host directories, and the host can't
  read plugin directories.
- **Don't pass large audio as a ByteArray**: the 1 MB per-process Binder budget
  will explode.
- **The FD belongs to the host.** After handing it over, don't close that
  `ParcelFileDescriptor` yourself. The file stays in your cache for the host to
  read; clean up the cache on your own schedule.

### `debugInfo` is user-visible

The plugin page has a collapsible "diagnostics" section at the bottom showing
`debugInfo` and the `detail` of the last error. Therefore:

- Put things that help **diagnosis** in there: model name / quantisation / actual
  steps / sample rates before and after resampling / RTF
- **Don't** put paths, tokens, or the user's text in it — the user can long-press and
  copy this, and will paste it into a bug report
- For failures, put the stack trace or raw native error in `PluginError.detail`
  (the host only shows it in the diagnostics block, never as the main message)
- `message` is still the one human-readable line; don't mix the two

This is far more useful than logcat alone: ordinary users can't run adb, but they
can take a screenshot.

### Streaming (optional)

When `req.streamAudio == true`, push with `out.chunk(pcm, rate, ch, isFinal)` while
generating, ≤ 64 KB per chunk. Useful for long text with early playback. The v1
host uses non-streaming by default.

---

## 9. The plugin form: rendered by the host

Plugins **write no layouts**. They return a field list; the host paints it in its
own theme.

```kotlin
override fun buildSettingsSchema(): UiSchema = UiSchema(
    intro = "Shown at the top of the page; supports **bold** and `code`.",
    items = listOf(
        UiItem("g1", UiType.GROUP_START, label = "Voice"),
        UiItem("speed", UiType.SLIDER, label = "Speed",
               value = "1.0", min = 0.5, max = 2.0, step = 0.1),
        UiItem("voice", UiType.CHOICE, label = "Voice preset",
               value = "default",
               options = listOf(UiOption("default", "Default"), UiOption("soft", "Soft"))),
        UiItem("g1e", UiType.GROUP_END),
        UiItem("dl", UiType.ACTION, label = "Download model", primary = true),
    ),
)

override fun onSettingChanged(key: String, value: String) { /* persist */ }
override fun onActionInvoked(key: String) { /* work, then notifySchemaChanged() */ }
```

### The 12 widget types

| Type | Purpose | Key fields |
|---|---|---|
| `INFO` | paragraph of text | `label` = body |
| `SECTION` | section heading (not collapsible) | `label` |
| `SWITCH` | toggle | `value` = `"true"` / `"false"` |
| `SLIDER` | numeric slider | `min` / `max` / `step` / `value` |
| `CHOICE` | single choice | `options` + `value` |
| `TEXT` | single-line input | `value` |
| `ACTION` | button | `key` (routed to `invokeAction`) |
| `PROGRESS` | **read-only** progress bar | `value` = 0–100 as a string |
| `STATUS` | **read-only** key → value row | `label` / `value` |
| `TEXTAREA` | multi-line input | `value` |
| `GROUP_START` | collapsible group start | `label` = title, `key` remembers state |
| `GROUP_END` | group end | — |

`UiType.MAX_KNOWN = 11` is the highest number the host understands. Use anything
higher and the host degrades it to plain text (no crash, but the widget is gone).
**The set is fixed on the host side; plugins can't introduce new types.**

### Values belong to the plugin

The host **persists nothing**. Every repaint calls `getSettingsSchema()` again.
Therefore:

- Put the *current* value into `UiItem.value`; never read it back from the host
- Persist changes yourself (SharedPreferences or your own files)
- When a change affects other fields (mode A greys out a slider), store first, then
  call `notifySchemaChanged()`

**Don't use `enabled=false` to hide things** — the user needs to see *why* something
is unavailable; visibility is the clue. If it truly shouldn't exist, don't return it.

### Card vs. form: where things go

| Location | Contents | Examples |
|---|---|---|
| Card (host-painted) | management actions | Enable / Activate / Enter / Uninstall |
| Plugin form (host-painted, content from plugin) | **functions** and parameters | Preview, download model, speed, voice |

Rule of thumb: **the plugin owns "what's possible" (capabilities), the host owns
"how it's triggered" (buttons).** "Preview" means the host starts a synthesis and
plays the result, so the button is host-painted — but it's a *function*, so it lives
in the plugin form rather than on the card.

---

## 10. Compatibility: when to bump the version

```kotlin
const val API_VERSION = 2         // AIDL surface version; bump only on breaking changes
const val API_WITH_SCHEMA = 2     // form methods exist from this version on
const val API_MIN_SUPPORTED = 1   // oldest plugin the host accepts
const val API_MAX_SUPPORTED = 2   // newest plugin the host accepts
```

### Two kinds of change

| Kind | Examples | Handling |
|---|---|---|
| **Extension** | new method, new field, new constant | `API_VERSION + 1`; old plugins keep working; the host gates new features by version |
| **Breaking** | removed method, changed field semantics | `API_VERSION + 1` **and** raise `API_MIN_SUPPORTED`; old plugins are rejected |

Nearly all changes are extensions. Before breaking anything, remember that every
plugin already shipped has to be rebuilt.

### New methods must go at the **end** of the AIDL file

Binder transaction codes are assigned in declaration order. Inserting in the middle
shifts every later code, and **an old plugin receives the wrong code — the call
crosses wires** (you ask for A, it runs B). That's far worse than a clean error.

### Decide "can I call this?" from the version, not from try-catch

Before calling `getSettingsSchema()`, the host checks
`pluginApi >= API_WITH_SCHEMA`. Why not try-catch: AIDL behaviour for unknown
methods isn't guaranteed — a synchronous call may read an empty Parcel and return
null or garbage, and `oneway` calls **silently do nothing**. Treating that as an
exception is unreliable.

---

## 11. Debugging

### Log tags

| Tag | Source |
|---|---|
| `PonkoPlugin` | plugin base class (SDK side) |
| `PonkoPluginConn` | one connection on the host (state machine, bind results) |
| `PonkoPluginSession` | host-side façade (scan, enable, speech routing) |
| `PonkoPluginPage` / `PonkoPluginUi` | host-side UI |

```bat
adb logcat -s PonkoPlugin PonkoPluginConn PonkoPluginSession
```

### Error codes

| Code | Name | Usually means |
|---|---|---|
| 1 | `NOT_READY` | engine not loaded (the host reloads once and retries) |
| 2 | `MODEL_INVALID` | missing or malformed model files |
| 3 | `OUT_OF_MEMORY` | not enough RAM; ask the user to free some |
| 4 | `CANCELLED` | **not an error**; the host wraps up silently |
| 5 | `INVALID_REQUEST` | empty text, missing reference audio |
| 6 | `PLUGIN_INTERNAL` | the plugin threw |
| 7 | `UNSUPPORTED` | unsupported model / mode |
| 8 | `TIMEOUT` | host-side timeout |
| 9 | `BUSY` | previous job still running (no queueing) |

`PluginError.message` is **localised by the plugin** and displayed verbatim by the
host, so write human language.

### Symptom → cause

| Symptom | Most likely cause |
|---|---|
| Plugin not listed at all | Missing `ACTION_PLUGIN` intent-filter, or `<service>` lacks `exported="true"` |
| `bindService` returns **false** | ① package is **stopped** (never activated) ② service disabled by the system ③ intent doesn't resolve |
| **SecurityException** | signature mismatch, or the host lacks the signature permission (reboot and retry) |
| Bound, then times out after 5 s | plugin process can't start: missing `.so`, wrong ABI, crash during init |
| First preview works, second says "engine not loaded" | wrong predicate in the plugin's state machine (see §7) |
| Runtime `NoSuchMethodError` | AIDL changed but the **SDK AAR wasn't republished**; plugin built against the old interface |
| Form shows "this widget needs a newer Ponko" | plugin used a `type > MAX_KNOWN` |

---

## 12. Constraints you must know

### The foreground service must start in `onBind`

```kotlin
override fun onBind(intent: Intent?): IBinder {
    goForeground(Stage.LOADING, "Preparing…")   // already done by the base class
    return binder
}
```

Android 12+ forbids starting a foreground service from the background. At
`onBind` the host has just called `bindService` and is still in the foreground, so
it always succeeds. Starting it later in `synthesize` can throw
`ForegroundServiceStartNotAllowedException`, the service never comes up, and
inference is killed midway.

### Large audio must travel through an FD

The per-process Binder budget is 1 MB. Sending more than that as a `ByteArray`
explodes — and it shows up as the nasty "short clips work, long text always
crashes" pattern.

### Keeping the screen awake is the host's job

A plugin's `PARTIAL_WAKE_LOCK` keeps the CPU alive, **not the screen**. The screen
is the host's responsibility via `FLAG_KEEP_SCREEN_ON`. Neither replaces the other.

---

## 13. Release checklist

- [ ] `versionCode` bumped (same-version overwrites make some ROMs cache the old dex)
- [ ] `capabilities` reflect real promises (samples use `TEST`, not `TTS`)
- [ ] Signed with the host's key; `apksigner verify --min-sdk-version 23` all true
- [ ] No launcher icon (activator only, no `CATEGORY_LAUNCHER`)
- [ ] On a clean device: Enable → Preview → audible, first try
- [ ] Preview twice or more (exercises the engine state machine)
- [ ] **"Stop" mid-generation ends it promptly** (check points are dense enough, host buttons reset)
- [ ] Clone mode with a picked reference audio actually produces output (host sends an FD; plugin decodes to 16 kHz)
- [ ] After changing "inference threads", restart the plugin and confirm `config.threads` is honoured
- [ ] `AUTOLOAD_ENGINE` is declared correctly (real models must **not** declare it, or
      merely opening the plugin tab pours several GB in)
- [ ] If not declared: the "Load engine" button appears, loads, unloads, and its label follows the state
- [ ] Disabling actually frees the plugin's model memory
- [ ] If AIDL changed: SDK republished and plugin rebuilt
- [ ] Identify builds by **sha256**, not byte size (adjacent builds can be byte-identical)

---

## 14. Further reading

- Design trade-offs and traps: [`../log/`](../log/) (Chinese only)
- Interface definitions: `ponko-plugin-sdk/src/main/aidl/` in the host project
- Complete example: `plugins/echo/` on the `Ponko-EX_test` branch
