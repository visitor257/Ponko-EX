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

### ⑦ Optional: show only some of the host's buttons (v9.1)

By default the host draws all of 加载 / 合成 / 播放 / 停止 / 保存. If you only need a
few of them, one line in `<service>` (or `<application>`) removes the rest:

```xml
<meta-data android:name="ponko.actions" android:value="reasoning,stop" />
```

| Value | Result |
|---|---|
| **absent** | everything is shown (existing plugins unaffected) |
| `"reasoning,stop"` | only those two |
| `""` (empty string) | none of them (the parameter area stays, so users can still type) |

**No AIDL change, no SDK re-release — one manifest line is enough.**

An id that does not exist (a typo, or an action this plugin never had) is dropped,
with one line in logcat saying which. One misspelt word will not wipe out the row.

> Why this exists: the only switch used to be `AUTOLOAD_ENGINE`, which does remove
> 加载 — at the cost of loading gigabytes of weights the moment you connect. Binding
> two unrelated concerns to one switch means neither works well.

### ⑧ Optional: give the card an "About" entry (v12.3)

Every card in the plugin page has an "About" button. It opens a plain-text dialog
that can be selected and copied in one tap. The content comes from here:

```xml
<!-- one short line -->
<meta-data android:name="ponko.about" android:value="VoxCPM2 local speech · MIT" />

<!-- a longer document in res/raw/about.txt -->
<meta-data android:name="ponko.about" android:resource="@raw/about" />
```

| Form | Use | Fits |
|---|---|---|
| `android:value` | inline string | a sentence or two |
| `android:resource` | `res/raw/*.txt`, `res/raw/*.md` | multi-paragraph notes (model licence, changelog, credits) |

Both `<service>` and `<application>` are read, **service first** (same as `ponko.actions`).

**If you don't declare it, the dialog shows "无"** — that is not an error, just two
characters. Existing plugins are unaffected.

> What's worth writing: version requirements, where the model came from and its
> licence, how to reach the author. People open "About" to answer "can I use this
> commercially" and "where did this model come from", not to read a feature list
> (the card already has one).

> **Why a manifest entry and not AIDL**: the about text is static and versioned with
> the APK. The bigger reason is that **it must be readable when the plugin cannot
> connect**. The moment a card most needs "what is this" is exactly when the plugin
> sits in stopped state or has failed to bind — over AIDL that would show "无" too.

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

## 5. AIDL quick reference (API v12)

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
| `getActionLabels()` | sync | v3: button label overrides, see §9 |
| `deleteModel(role)` | oneway | v6: user wants a model slot's file deleted |

> Only five methods are synchronous (version, ready, status, form, action labels).
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
| `onUiPatch(key, value, append)` | v4: update one control in place (log / progress / status) |
| `onImage(fd, w, h, mime)` | v4: deliver a result image |
| `requestSynthesis()` | v7: ask the host to start a synthesis (user tapped a button in your form) |
| `onAudioFile(key, fd, name)` | v7: audio for one specific history entry |
| `selectTab(groupKey, tabKey)` | v8: ask the host to switch to a page |
| `onImageWithKey(key, fd, …)` | v11: deliver an image to a named `IMAGE` control |
| `onScrollTo(key)` | v12: ask the host to scroll to an item |

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

### What the base class exposes

| Method | Version | Purpose |
|---|---|---|
| `notifySchemaChanged()` | v2 | Form data changed — repaint |
| `notifyUiPatch(key, value, append)` | v4 | Update one control in place, **no full repaint** |
| `notifyImage(fd, w, h, mime)` / `notifyImage(file, …)` | v4 | Deliver an image (key optional when there is exactly one `IMAGE`) |
| `notifyImage(key, bitmap)` / `notifyImage(key, file)` | v11 | Deliver an image to a named `IMAGE` (required when there are several) |
| `notifyRequestSynthesis()` | v7 | Ask the host to synthesise using the form's current values |
| `notifyAudioFile(key, fd, name)` | v7 | Audio for one history entry |
| `notifySelectTab(groupKey, tabKey)` | v8 | Ask the host to switch pages |
| `notifyScrollTo(key)` | v12 | Ask the host to scroll to an item |
| `requestEngineLoad()` | v12.2 | **Load the engine** (through the base-class state machine) |
| `requestEngineClose()` | v12.2 | **Release the engine** (same) |
| `pluginEngineState` | v12.2 | Current engine state, for drawing your own UI |

The `callback` field is private — without these methods you cannot reach the
callback at all and would have to hold the IBinder yourself.

### Capabilities describe a promise, not a behaviour

| Constant | Meaning | How the host uses it |
|---|---|---|
| `TTS` | text → audio | Selected by the "speak the answer" route |
| `ASR` | audio → text | reserved |
| `IMG` | image generation | reserved |
| `TEST` | test / sample plugin | Excluded from speech routing, but can still be synthesised and played. **Whether the engine loads on connect is decided separately by `AUTOLOAD_ENGINE`** |

**Sample plugins must declare `TEST`, never `TTS`.** They emit fixed tones, not
speech; declaring `TTS` means the host will pick them for real read-aloud and the
user hears an inexplicable arpeggio.

### 6.1 `AUTOLOAD_ENGINE`: load timing is your call

**Nothing loads automatically by default.** The engine is loaded when the user taps
"Load engine" or "Synthesize".

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
| User taps "Synthesize" | synthesises directly | loads first, then synthesises (no manual tap needed) |

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
   "Load engine" or "Synthesize". Repeat calls are free.
2. **After a job the state returns to `READY`, not `UNLOADED`** — unless the engine
   genuinely failed to load. (An early version keyed off "model size > 0", so a
   model-less plugin was marked unloaded right after its first synthesis and the
   second synthesis reported "engine not loaded". See `log/`.)
3. **`closeEngine` must be idempotent.** The host calls it when the user taps
   "Unload engine", on disable, on page
   destroy, and when the process is reclaimed.

`modelBytes` is **display-only** and plays no part in state transitions. Filling 0
for a model-less plugin is perfectly normal.

### There is no "unloaded" callback

`IPonkoCallback` has `onEngineLoaded` / `onEngineError` — both outcomes of loading —
but **no `onEngineUnloaded`**. So the host can only poll `getStatus().state` to know
whether unloading has finished.

What that means for you:

- Don't rely on the host announcing anything inside `closeEngineBlocking()` — just free
  synchronously and return
- To make it feel fast, **return early**. The host polls until `UNLOADED`, so the longer
  `closeEngineBlocking` blocks, the longer the user stares at "unloading…"
- If you're mid-inference when it's called, the SDK sets the cancel flag and you must
  check `throwIfCancelled()` in your loop. Sparse checks make unloading slow — the host
  just sits there waiting
- A `synthesize` arriving during the unload window now gets **`NOT_READY`** (not `BUSY`),
  because nothing is actually generating; "the previous clip is still being generated"
  would be a lie

> An earlier SDK returned `BUSY` in that window, so a user who unloaded and then tapped
> synthesis saw "the previous clip is still being generated, please wait". **Fixed** (the
> SDK now has a separate `closing` flag). The fix needs the AAR republished and plugins
> rebuilt to take effect.

---

## 8. Performing a synthesis

### What the request actually carries

Since Ponko 2.0.0 the plugin page has a "synthesis parameters" block that really
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
make cancellation slow — the host sits on "stopping…" forever, the Synthesize button
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

### The reverse direction: make the host start a synthesis (v7)

Normally the host sends `synthesize` to you. The reverse case: you put a
「开始推理」button in your own form (`invokeAction`) and want the synthesis to go
through **the host's path** — because the host already handles parameters,
the reference-audio FD, progress display and button state.

```kotlin
override fun onActionInvoked(key: String) {
    if (key == "start") notifyRequestSynthesis()
}
```

It is equivalent to the user tapping the host's own 合成 button: **the same path**,
so validation, reference audio, progress and cancellation all behave identically.
There is no second synthesis path, and therefore no second set of bugs.

The difference from `synthesize`: that one is the host telling you to work; this one
is you asking the host to give the order. Either way it lands in your
`synthesizeBlocking`.

### Loading / releasing the engine from your own button (v12.2)

**This is the easiest trap to fall into, and the symptom is alarming.**

`loadEngineBlocking()` is `protected`, so it looks callable. But it is only the
**blocking implementation** — the state machine, the foreground service and the
callbacks all live elsewhere. Call it directly and:

| What you see | Why |
|---|---|
| The host's「引擎」line always says "not loaded" | the base class `state` is still `UNLOADED` |
| You tap synthesize, wait 1–2 minutes, and get **two results** | the host sees `NOT_READY`, loads the engine itself, then **re-sends the synthesis** |
| No foreground-service notification | no `onEngineLoaded`, so the notification never sits in "loading" |

The cause is simple: `loadEngine` / `closeEngine` / `getStatus` / `isEngineReady`
are all inside the binder, and the binder is private — **a plugin cannot reach any
of them**. That was a genuine SDK gap.

```kotlin
override fun onActionInvoked(key: String) {
    when (key) {
        "load"  -> requestEngineLoad()      // not loadEngineBlocking
        "close" -> requestEngineClose()     // not closeEngineBlocking
    }
}

// drawing your own status label
val loaded = pluginEngineState == EngineState.READY ||
             pluginEngineState == EngineState.RUNNING
```

| Method | Returns | Notes |
|---|---|---|
| `requestEngineLoad(models = null, config = null)` | `true` = actually started | idempotent: `false` if already loaded or loading, and nothing happens |
| `requestEngineClose()` | `true` = actually started | `false` if it was not loaded |
| `pluginEngineState` | `EngineState.*` | `UNLOADED` / `LOADING` / `READY` / `RUNNING`, O(1) |

**Omitting `models` is fine** — the base class caches the `ModelBundle` and
`EngineConfig` the host supplied last time and reuses them. So a plugin-initiated
load will not lose the user's imported model.

`closeEngineBlocking()` is the same story: calling it only frees memory while
`state` stays `READY`, so the host believes the engine is still there and keeps
sending synthesis requests.

> Use the `*Blocking` variants directly only when you deliberately want to bypass
> the state machine (unit tests, for instance). Normal flows go through `request*`.

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

### Text formatting: a very small subset

`intro` and `INFO` bodies support **two** inline formats:

| Syntax | Result |
|---|---|
| `**bold**` | bold |
| `` `monospace` `` | monospace |

**Everything else is shown verbatim** — `#` headings, `- ` lists, `[links](url)` and
tables are not parsed; the symbols appear on screen. `label` / `help` / `value` are
**not parsed at all**, plain text only.

> ⚠ This used to be inconsistent: the guide claimed bold and code were supported while
> the host merely did `text = t`, so plugins that followed the guide rendered literal
> asterisks. **Only since Ponko 2.0.0 is it actually true**, and only for the two
> formats above.
>
> Write your copy assuming bold and code are all you get — don't count on lists or headings.

### The 26 widget types

Read them in three groups: **settings** (the user edits them, delivered through
`onSettingChanged`), **process and output** (display only, fed by `notifyUiPatch`
or `notifyImage`), and **layout** (no control of their own, they only rearrange).

#### ① Settings (v1–v2, 12 types)

| Type | No. | Purpose | Key fields |
|---|---|---|---|
| `INFO` | 0 | paragraph of text | `label` = body |
| `SECTION` | 1 | section heading (not collapsible) | `label` |
| `SWITCH` | 2 | toggle | `value` = `"true"` / `"false"` |
| `SLIDER` | 3 | numeric slider | `min` / `max` / `step` / `value` |
| `CHOICE` | 4 | single choice | `options` + `value` |
| `TEXT` | 5 | single-line input | `value` |
| `ACTION` | 6 | button | `key` (routed to `invokeAction`); `value` can name a host action, see below |
| `PROGRESS` | 7 | **read-only** progress bar | filled by the host; `value` is only the initial one |
| `STATUS` | 8 | **read-only** key → value row | `label` / `value` |
| `TEXTAREA` | 9 | multi-line input | `value` |
| `GROUP_START` | 10 | collapsible group start | `label` = title, `key` remembers state |
| `GROUP_END` | 11 | group end | — |

#### ② Process and output (v4 onward, 3 types)

| Type | No. | Purpose | Fed by |
|---|---|---|---|
| `LOG` | 12 | log pane (monospace, long-press to copy) | `notifyUiPatch(key, text, append = true)` |
| `IMAGE` | 13 | result image | `notifyImage(...)` |
| `AUDIO` | 14 | playback bar (play / seek / time / save) | `notifyAudioFile(key, fd, name)` |

These three **do not participate in `setSetting`** — the user cannot edit them, and
`patch` only understands the first (images go through `notifyImage`, `AUDIO` is the
host's player).

#### ③ Layout (v5 onward, 11 types)

| Type | No. | Notes |
|---|---|---|
| `TABS_START` | 15 | tab group start. `key` remembers "which page you were on" |
| `TAB` | 16 | page start. `key` is what `notifySelectTab` refers to |
| `TABS_END` | 17 | tab group end |
| `MODEL_SLOT` | 18 | model file slot, `value` = `ROLE_*` |
| `HOST_SLOT` | 19 | mount point for the host's function block; `value` can pick a segment |
| `ROW_START` / `ROW_END` | 20 / 21 | one horizontal row; `value` sets width shares |
| `GRID_START` / `GRID_END` | 22 / 23 | equal-width grid; `value` = column count |
| `DIVIDER` | 24 | a rule (turns vertical inside a ROW) |
| `SPACER` | 25 | empty space (height in a column; weight in ROW/GRID) |

`UiType.MAX_KNOWN = 25` is the highest number the host understands. Use anything
higher and the host degrades it to plain text (no crash, but the widget is gone).
**The set is fixed on the host side; plugins can't introduce new types.**

### Tabs (v5 onward)

```kotlin
UiItem("t", UiType.TABS_START, value = "param")   // value = key of the default page
UiItem("p", UiType.TAB, label = "参数")
UiItem("txt", UiType.TEXTAREA, label = "要合成的内容")
UiItem("r", UiType.TAB, label = "结果")
UiItem("cmp", UiType.AUDIO, label = "合成结果")
UiItem("", UiType.TABS_END)
```

| Rule | Notes |
|---|---|
| Everything after a `TAB` belongs to that page until the next `TAB` or `TABS_END` | order semantics, not "grouping" |
| `TABS_START.value` is the page selected by default | once the user switches, the host remembers **their** choice |
| The outermost group is swipeable; nested ones are not | a nested pager's horizontal gesture fights the page's vertical scroll |
| Nesting is allowed (pages inside pages) | unlimited depth, but more than two is painful on a phone |

To switch pages yourself use `notifySelectTab(groupKey, tabKey)` (v8) —
**the host will not do it for you**.

### Model slots (v6 onward)

When the plugin needs the user to pick a model file, use this instead of writing
your own file picker:

```kotlin
UiItem("m", UiType.MODEL_SLOT, label = "主模型", value = PluginConst.ROLE_MAIN)
```

The host handles the picker, copies the file into **your private directory**,
reports the path, shows name and size, and offers delete. You just take the
ready-made `File` from the `models` argument.

`value` uses `ROLE_MAIN` / `ROLE_BASE_LM` / `ROLE_ACOUSTIC` / `ROLE_MMPROJ` /
`ROLE_VAE` / `ROLE_TOKENIZER` — these are **slot identities**, one file per role.
A custom string also works; the host simply doesn't know its semantics.

### The host function block: `HOST_SLOT` (v7; segmented since v9.1)

The 加载 / 合成 / 播放 / 停止 / 保存 row is pinned under the title by default.
One `HOST_SLOT` moves it wherever you want:

```kotlin
UiItem("", UiType.HOST_SLOT)     // move the whole block
```

Moved whole, the internal order is fixed: **title → button row → parameters →
diagnostics**. To arrange it yourself, move it by segment:

| `value` | What moves |
|---|---|
| `"actions"` | the 功能 heading, button row, action help, status line ("正在合成…") |
| `"params"` | the host's parameter area (target text / mode / reference audio / transcript / seed / threads) |
| `"diag"` | diagnostics (collapsed by default) |
| empty | the whole block (default) |

```kotlin
UiItem("txt", UiType.TEXTAREA, label = "要合成的内容")
UiItem("", UiType.HOST_SLOT, value = "actions")     // the start button, under the text
UiItem("log", UiType.LOG, label = "进度")            // progress, under the button
```

| Rule | Notes |
|---|---|
| Each segment honours only its **first** slot | a second `actions` slot is empty |
| Segments you do **not** claim go back to the top | if you only claim `actions`, the parameter area stays under the title (otherwise you'd have nowhere to type) |
| Whole-block and segmented are **mutually exclusive** within a frame | mixing them makes the whole-block slots return null |
| A misspelt segment name | treated as whole-block, plus one logcat warning |
| All three claimed | the top reserves nothing (no empty card left behind) |

**Why the plugin can't just redraw the whole block**: behind those buttons is the
host's state machine (how far the engine has loaded, whether a synthesis is running,
the reference-audio FD, saving to the music library). Redrawing means copying all of
that over, and two copies inevitably disagree. This only changes the **mount point**;
the logic still exists once.

### Naming a host action: `ACTION`'s `value` (v9 onward)

Normally `ACTION` is your button (taps go to `invokeAction`). If `value` is one of
the host action ids, it becomes **that host button**, drawn where you put it:

```kotlin
UiItem("start", UiType.ACTION, value = HostActionId.REASONING)
```

| `HostActionId` | Host action |
|---|---|
| `ENGINE` | load / unload the engine (one button, two states) |
| `REASONING` | synthesise (produces a file, does not play) |
| `PLAY` | replay the last artefact |
| `STOP` | stop (a real interruption) |
| `SAVE` | copy into the system「音乐/Ponko」 |

A named button **disappears from the top row** (never drawn twice); its label is
still overridable through `ActionLabel`.

> This is "relocation", not "creation": you cannot make the host offer an extra
> action, only decide where an existing one is drawn.

### Laying things out yourself (v10 onward)

Since v2, `items` had been a **flat vertical list** — two sliders side by side was
impossible. v10 adds containers:

```kotlin
// two sliders on one row, half each
UiItem("", UiType.ROW_START)
UiItem("speed", UiType.SLIDER, label = "语速", …)
UiItem("pitch", UiType.SLIDER, label = "音高", …)
UiItem("", UiType.ROW_END)

// 1:2 width
UiItem("", UiType.ROW_START, value = "1,2")

// three-column button matrix; the fourth wraps
UiItem("", UiType.GRID_START, value = "3")
UiItem("v1", UiType.ACTION, label = "音色 A")
UiItem("v2", UiType.ACTION, label = "音色 B")
UiItem("v3", UiType.ACTION, label = "音色 C")
UiItem("v4", UiType.ACTION, label = "音色 D")
UiItem("", UiType.GRID_END)
```

| Container | `value` |
|---|---|
| `ROW_START` | empty = equal shares; `"1,2"` = by share. Missing shares default to 1; `0` or negative is treated as 1 |
| `GRID_START` | 2–4 columns; empty or invalid = 2. **Every column is equal width**; capped at 4 (beyond that, labels get eaten by ellipses) |

Same stack syntax as `GROUP_START` / `TABS_START`, and **nesting is free**: ROW
inside GROUP, GROUP inside ROW, GRID inside TABS all work.

| Fine inside ROW/GRID | Poor (works, but very narrow) |
|---|---|
| `SLIDER` `TEXT` `SWITCH` `ACTION` `CHOICE` `STATUS` `INFO` | `TEXTAREA` `LOG` `IMAGE` `TABS_START` |

When the last grid row is not full it **does not reserve space** — a single item in
a 3-column grid still occupies one third, with blank space to the right, rather than
stretching across the row.

**`SPACER` is the piece that completes `ROW`**: without it a row can only divide
evenly, and "one group left, one group right" is impossible.

```kotlin
UiItem("", UiType.ROW_START, value = "1,2,1")
UiItem("save", UiType.ACTION, label = "保存")
UiItem("", UiType.SPACER)            // takes the middle 2 shares, pushing the sides apart
UiItem("load", UiType.ACTION, label = "加载")
UiItem("", UiType.ROW_END)
```

### Styling is not yours

| Yours | The host's |
|---|---|
| which controls exist | colours (all from the host theme) |
| order, grouping, tabs | type size, line height |
| horizontal / grid / arbitrary nesting | corner radius, padding |
| width share of each item | dark-mode adaptation |

**Total freedom over structure, none over styling.** Not out of caution: a plugin
cannot read the host's theme object, so any colour it hard-codes becomes a second
palette that stays behind when the user switches themes.

### The host will not decide for you

This matters more than any individual control. The host's behaviour on your form has
exactly two categories:

1. **The control's own semantics** — dragging a slider, toggling a switch,
   highlighting a choice, folding a group
2. **The host's own business** — the playback bar reflecting the player, when an
   action button is enabled

Otherwise the default is **do nothing**. So:

| You want | You say so |
|---|---|
| switch to a page | `notifySelectTab(groupKey, tabKey)` |
| scroll to an item | `notifyScrollTo(key)` |
| an image to land on a specific `IMAGE` | `notifyImage(key, bitmap)` |
| the log to follow new output | nothing to do — it follows only if you were already at the bottom, and never drags you back mid-history |
| the position after a repaint | nothing to do on v12.1+ — it survives because heights no longer dip, not because the host writes an old value back; **on v12.0 it jumps to the top and the plugin cannot fix it** |

> **v12.0 and v12.1 both report `pluginApi == 12`, but they repaint differently.**
> Before v12.1 the tab pager's height was set from a pre-draw listener, so on a rebuild
> the content was briefly shorter; `ScrollView.onLayout` ends with
> `scrollTo(mScrollX, mScrollY)`, which re-clamps against the current child height, and
> clamp only shrinks — it never grows back. So **do not treat "the position survives a
> repaint" as a contract**: if you need to land somewhere, say
> `notifyScrollTo(key)`.

**When the target is ambiguous the host drops it and logs, never guesses.** Guessing
right saves you one key; guessing wrong puts something where it does not belong, in a
way that looks like the plugin did it — and that is the hardest kind of bug to trace,
because you will suspect your own code first.

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
| Plugin form (host-painted, content from plugin) | **functions** and parameters | Synthesize, play, download model, speed, voice |

Rule of thumb: **the plugin owns "what's possible" (capabilities), the host owns
"how it's triggered" (buttons).** "Synthesize" means the host runs inference and
writes a file (it does not play it), so the button is host-painted — but it's a
*function*, so it lives in the plugin form rather than on the card.

### Which buttons an audio plugin gets (worth knowing)

Declaring `TTS` or `TEST` puts these **host-painted** buttons at the top of the plugin
form. They aren't defined by the plugin, but **their labels can be overridden**
(see the next section):

| Button | When present | What happens |
|---|---|---|
| Load engine / Unload engine | only when `AUTOLOAD_ENGINE` is **not** declared | host calls `loadEngine` / `closeEngine` |
| Synthesize | always | host sends a `synthesize` and **writes the file; it does not play it** |
| Play | **after output exists** | replays the existing file, **no inference** |
| Stop | enabled during synthesis | host calls `cancel()`; you answer `CANCELLED` |
| Save | after output exists | copies the audio into the system Music/Ponko library |

So: **don't add your own "synthesize" or "play" button to `UiSchema`** — the host already
handles both. Spend the form on what's genuinely yours (model choice, voice,
parameters, downloads).

#### Only want a few of them?

Add one `ponko.actions` line to the manifest — see ⑦ in §3. **No code change, no SDK
rebuild.**

#### "Synthesize" and "Stop" share one cell

They are **mutually exclusive**: outside a synthesis only Synthesize is meaningful,
during one only Stop is. So they occupy **the same cell** — after tapping start,
Synthesize disappears and Stop takes its place, with identical position and width.

This is host-internal layout; there is nothing for you to do. But if you moved
`REASONING` elsewhere via `HostActionId`, then `STOP` gets the cell to itself (no
empty slot left behind).

> **Synthesising and playing are two separate actions with two separate buttons.**
> Listening again is a **frequent** action, and re-synthesising on a phone costs
> 1–2 minutes — so don't try to save a button by playing automatically on completion.
> That forces every generation to be listened to, which makes the plugin unusable in
> a quiet room.

### Renaming the host's buttons (SDK v3 and later)

The default labels are written for "there is an engine to load". If your plugin isn't
that, override them:

```kotlin
override fun buildActionLabels(): Map<String, String> = mapOf(
    ActionLabel.ENGINE_LOAD to "Load model 3.5G",
    ActionLabel.ENGINE_UNLOAD to "Free model",
)
```

**Text only.** Position, count, colour, enabled state and what a tap does all stay with
the host — a plugin can't create a button, only give its own cell a different name.

#### Available keys

| key | Host default | When shown |
|---|---|---|
| `ActionLabel.ENGINE_LOAD` | Load engine | engine not ready |
| `ActionLabel.ENGINE_UNLOAD` | Unload engine | engine ready (same button, other state) |
| `ActionLabel.REASONING` | Synthesize | always. **Runs inference, produces a file, does not play it** |
| `ActionLabel.PLAY` | Play | always (enabled once output exists). Replays only, no inference |
| `ActionLabel.STOP` | Stop | always (enabled during synthesis) |
| `ActionLabel.SAVE` | Save | always (enabled once output exists) |

**Any key you leave out keeps the host default.** You don't have to override the whole
set — Echo overrides two and leaves the rest, which doubles as a live check that both
sources can coexist in the same row.

#### When it's worth overriding

| Your plugin | Suggestion | Why |
|---|---|---|
| Local model | `engine.load` → "Load model 3.5G" | the size tells the user how long this takes and what it costs in RAM |
| Network API | `engine.load/unload` → "Connect / Disconnect" | there is no model; calling it an engine is simply wrong |
| Model can stay resident | `engine.unload` → "Free memory" | the user cares about RAM, not about the verb "unload" |
| Toy / test plugin | `reasoning` → "Run a test" | it doesn't synthesise speech; "Synthesize" implies producing a voice |

#### Hard limits

| Case | Result |
|---|---|
| Unknown key | that entry is **dropped**; the rest still apply |
| Empty value | dropped |
| Longer than 12 characters | **dropped entirely** (not truncated), falls back to the host default |
| `buildActionLabels()` throws | everything falls back to defaults; **the plugin stays usable** |

Why over-long values are dropped rather than truncated: "Load model 3.5…" is harder to
understand than the original default it replaced.

#### What not to do with it

- **Don't make a destructive action sound harmless** (calling "Stop" something like
  "Save"). Users read the label and tap it, then lose the clip being generated. This is
  the one abuse I can't catch in code.
- Don't do slow work here: the method runs on a Binder thread and is called when the
  form opens.

---

## 10. Compatibility: when to bump the version

```kotlin
const val API_VERSION = 12                  // AIDL surface version; any change bumps it
const val API_WITH_SCHEMA = 2               // the form methods
const val API_WITH_ACTION_LABELS = 3        // button labels overridable
const val API_WITH_RICH_UI = 4              // log pane / images / playback bar, onUiPatch
const val API_WITH_TABS = 5                 // tabs
const val API_WITH_MODEL_IMPORT = 6         // model slots + deleteModel
const val API_WITH_SYNTH_REQUEST = 7        // reverse synthesis / per-entry audio / HOST_SLOT
const val API_WITH_TAB_SELECT = 8           // plugin switches pages
const val API_WITH_HOST_ACTION_ITEMS = 9    // ACTION can name a host action
const val API_WITH_LAYOUT_CONTAINERS = 10   // ROW / GRID / DIVIDER / SPACER
const val API_WITH_IMAGE_KEY = 11           // images can name their target
const val API_WITH_SCROLL_TO = 12           // plugin controls the scroll position
const val API_MIN_SUPPORTED = 1             // oldest plugin the host accepts
const val API_MAX_SUPPORTED = 12            // newest plugin the host accepts
```

### What each version added

| Version | Content | On an older host |
|---|---|---|
| v1 | engine + synthesis + audio | — |
| v2 | `UiSchema` form | no form page |
| v3 | overridable button labels | host defaults |
| v4 | `LOG` / `IMAGE` / `AUDIO`, `onUiPatch`, `GROUP_*` | those three degrade to text blocks |
| v5 | `TABS_*` | tabs degrade; all content piles up at the top |
| v6 | `MODEL_SLOT` + `deleteModel` | slot degrades; users cannot pick model files |
| v7 | `requestSynthesis` / `onAudioFile` / `HOST_SLOT` | block stays under the title; history can only play the latest |
| v8 | `selectTab` | tabs only switchable by the user |
| v9 | `HostActionId` | function buttons stay at the top |
| v10 | `ROW` / `GRID` / `DIVIDER` / `SPACER` | **everything becomes one vertical column**; side-by-side items stack |
| v11 | `onImageWithKey` | with several images, only the first gets filled |
| v12 | `onScrollTo` | position follows host defaults; no active jumping |
| v12.2 | `requestEngineLoad` / `requestEngineClose` / `pluginEngineState` | only `*Blocking` is reachable, so the state machine never updates — the host reloads and re-sends the synthesis |

> **v12.1 is not in the table above on purpose**: it only changes host internals
> (a `ViewPager` subclass measures every page in `onMeasure` and hands the max to
> super), and `API_VERSION` stays at 12. So the same `pluginApi == 12` can jump to the
> top on repaint (v12.0) or not (v12.1) — **the version number cannot tell them apart**.
> When you need a definite position, always call `notifyScrollTo(key)` instead of
> relying on the default.

Gate compatibility checks on `pluginApi >= API_WITH_xxx`, **not** on `== latest`.

> The v10 row deserves attention: layout containers on an older host are not
> "slightly uglier", the **structure collapses**. If your plugin uses ROW but must run
> on older hosts, at least make sure it still reads sensibly when items are simply
> stacked vertically — i.e. each item inside a ROW should make sense on its own.


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
| First synthesis works, second says "engine not loaded" | wrong predicate in the plugin's state machine (see §7) |
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
- [ ] On a clean device: Enable → Synthesize → Play → audible, first try
- [ ] Synthesize twice or more (exercises the engine state machine)
- [ ] **"Stop" mid-generation ends it promptly** (check points are dense enough, host buttons reset)
- [ ] Clone mode with a picked reference audio actually produces output (host sends an FD; plugin decodes to 16 kHz)
- [ ] After changing "inference threads", restart the plugin and confirm `config.threads` is honoured
- [ ] `AUTOLOAD_ENGINE` is declared correctly (real models must **not** declare it, or
      merely opening the plugin tab pours several GB in)
- [ ] If not declared: the "Load engine" button appears, loads, unloads, and its label follows the state
- [ ] "Unload engine" resets the button afterwards (it does not stay on "Unload", with a
      second tap loading the model back in)
- [ ] Synthesize immediately after unloading does not report "the previous clip is still
      being generated"
- [ ] Once output exists, "Play" replays without re-running inference
- [ ] `intro` / `label` / `help` are written as plain text, no markdown lists or headings
- [ ] Any overridden button label is <= 12 characters, and the keys you did not override
      still show the host default (not an empty button)
- [ ] A renamed button still does what its new name says (you did not relabel "Stop")
- [ ] With several `IMAGE` controls you use `notifyImage(key, bitmap)`, not the keyless variant
- [ ] Jumping somewhere after a repaint goes through `notifyScrollTo(key)` (the host will not do it)
- [ ] Controls inside ROW / GRID still show their labels at half-screen width (nothing eaten by ellipses)
- [ ] v10+ layout containers still read sensibly in the degenerate single-column case
- [ ] Every id in `ponko.actions` is an action this plugin actually has (typos are dropped)
- [ ] Disabling actually frees the plugin's model memory
- [ ] If AIDL changed: SDK republished and plugin rebuilt
- [ ] Identify builds by **sha256**, not byte size (adjacent builds can be byte-identical)

---

## 14. Further reading

- Design trade-offs and traps: [`../log/`](../log/) (Chinese only)
- Interface definitions: `ponko-plugin-sdk/src/main/aidl/` in the host project
- Complete example: `plugins/echo/` on the `Ponko-EX_test` branch
