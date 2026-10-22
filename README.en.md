# Ponko-EX · VoxCPM2

A speech-synthesis plugin for [Ponko](https://github.com/visitor257/Ponko):
[VoxCPM2](https://modelscope.cn/models/OpenBMB/VoxCPM2) (OpenBMB, 2B) running **offline** on the phone.

[中文](README.md)

**A 4.6 MB plugin plus 3.5 GB of weights (downloaded on first run) — a TTS with no network.**
No server, no API key, no telemetry. It works in airplane mode.

|  |  |
|---|---|
| Host | Ponko **2.0.0** or newer (the `LiteRT-Chat` main line) |
| Platform | Android 9+ (minSdk 28) · **arm64-v8a only** |
| Engine | llama.cpp-omni's `voxcpm2_runtime` plus a hand-written JNI (statically linked, 5.8 MB) |
| Licence | Code MIT · VoxCPM2 weights Apache-2.0 (see [NOTICE.md](plugins/voxcpm2/NOTICE.md)) |

## What it does

- **30 languages and 9 Chinese dialects**, no language tag needed — the model works it out
- **Voice design**: `(young woman, soft and sweet)Hello there` — describe a voice in
  parentheses and get one that never existed
- **Zero-shot cloning**: 5–10 seconds of clean audio is enough to copy a timbre
- **Continuation cloning**: reference audio *plus* its transcript, reproducing pauses and
  intonation as well
- **48 kHz output**, straight out of the AudioVAE decoder, no extra upsampler

## Install

1. Install `Ponko-EX_VoxCPM2_release.apk`
2. Open Ponko → **Plugins** → enable **VoxCPM2**
3. On the plugin page, under **1 · Weights**, tap **Download all weights** (3.55 GB, resumable, **use Wi-Fi**)
4. Tap **Load model** at the top of the page (3.5 GB takes roughly 10–30 s); once the status reads *ready*, synthesise

> Loading is **a tap you make yourself**. Since 2.0.0 the host no longer loads every
> plugin's engine on connect — only lightweight plugins that declare `AUTOLOAD_ENGINE`
> are loaded automatically. This one needs 3.5 GB, so it deliberately does not declare it.
> Tapping **Synthesise** works too (it loads first, then synthesises), you just don't get a
> load-progress view. Tap **Release model** whenever you want the memory back.

> The action row is **one action per button**: `Load/Release model · Synthesise · Play ·
> Stop · Save`. **Synthesise only runs inference and writes a file — it makes no sound**;
> tap **Play** afterwards to hear it (the host's hint says so too). Play replays the last
> file **without re-running inference** — five seconds of speech takes 1–2 minutes on a
> phone, so don't reach for Synthesise just to hear the same line again.
>
> The page also carries a **"listen" transport bar** (SDK v4 `UiType.AUDIO`). It drives the
> **same player** as the Play button above it (the host's own), but you can see the time and
> drag the position — the only way to get to one sentence in a one-to-two-minute clip.
>
> The plugin names those buttons (SDK v3 `buildActionLabels()`). The host's defaults are
> *Load engine / Unload engine / Synthesise / Play / Stop / Save*; this plugin overrides two
> of them — it loads a model, not an engine, and unloading leaves the file on disk (hence
> "release"). Play / Stop / Save keep the host defaults.

> **The host has to know v4.** This build is compiled against SDK v4 (log pane / transport
> bar), so it needs a host that carries the 2026-10-22 SDK v4 update (Ponko 2.0.0).
> Anything older (`API_MAX_SUPPORTED = 3`) refuses to bind with "plugin SDK v4
> incompatible" — the gate rejects it before it can hand over a schema, so there is no
> fallback to plain text.
>
> For the record, the direction is now one-way: since `181dcf5` the host only rejects
> plugins **older** than its minimum. A plugin newer than the host connects normally and
> its unknown controls degrade to a "label + value + update Ponko" text block — so the
> "your plugin is too old" prompt no longer fires at a plugin that is already current.

> **The signature must match the host.** Ponko protects plugin binding with a
> `signature`-level permission, so a mismatched key makes `bindService` throw
> `SecurityException` and the UI only says "binding failed".
> This build is signed with Ponko's main-line `ponko-release.jks`; install it on a host
> built from another line (e.g. `hw`) and you have to re-sign.

## Using it

### Host side: synthesis parameters

Ponko 2.0.0's plugin page has a *synthesis parameters* block, and these values really do
reach `SynthesisRequest`:

| Parameter | Notes |
|---|---|
| Text | No hard character cap (the plugin never truncates). Phone RTF is 10–30, so 40 characters already means minutes |
| Mode | plain / clone / continuation |
| Reference audio | Picked from a file; the host opens it and passes an FD, the plugin decodes and resamples to 16 kHz |
| Transcript | Required for continuation only |
| Seed | `0` = random. Pin it to reproduce the same delivery |
| Threads | auto (cores−1) / 2 / 4 / 6; takes effect on the next engine load |

Pressing **Stop** mid-generation really interrupts: the plugin turns the host's cancel
into `nativeCancel()`, and the native side checks it on every audio chunk — normally out
within a few seconds, depending on which stage it is stuck in.

### Plugin side: its own settings

The host never sends `cfg` / `timesteps`; sensible ranges depend entirely on the model,
so the plugin exposes them in its own UI:

| Setting | Default | Range | Notes |
|---|---|---|---|
| CFG guidance | 2.0 | 1.0–4.0 | Higher sticks closer to the text, lower is freer |
| Diffusion steps | 10 | 4–24 | More is finer, and linearly slower |
| Voice design description | empty | — | Equivalent to the host's `voiceDescription`; if both are empty no prefix is added |

The page is ordered the way you use it: a **run status** block at the top (engine /
weights / storage / device / last synthesis / notices), a **listen** transport bar, then
**1 · Weights**, **2 · Synthesis parameters**, an explanation block, and **run log** at
the bottom.

The log is the host's `UiType.LOG` (SDK v4): fixed 160 dp tall, scrolling internally,
long-press to copy, last 120 lines only. New lines arrive as **incremental patches**
(`notifyUiPatch`) rather than a full page rebuild — so the log keeps its scroll position
and the *voice design* textarea you are typing into keeps its focus and content.
Scrolling back through history is safe too: new lines don't yank you to the bottom (the
host only follows the tail when the pane was already there).
Download progress works the same way: it only touches the progress bar.

## The three modes

| Mode | Needs | Good for |
|---|---|---|
| Plain TTS (0) | text | General reading, the default |
| Clone (1) | reference audio | A particular timbre, arbitrary content |
| Continuation (2) | reference audio + transcript | Reproducing a performance: rhythm, pauses, tone |

**Choosing reference audio**: 5–10 seconds, one speaker, no music, no reverb. wav / mp3 /
m4a / flac all work (decoded through `MediaCodec`, then resampled to 16 kHz).

## Weights

Not in the APK — 3.5 GB is not shippable. Downloaded on first use into the plugin's own
external private directory:

```
Android/data/com.litertchat.plugin.voxcpm2/files/models/
```

| Source | BaseLM | Acoustic | Total | Notes |
|---|---|---|---|---|
| **Standard** (default) | Q8_0 1.73 GB | F16 1.83 GB | **3.55 GB** | Repository declares `apache-2.0` |
| Compact | Q4_K_M 1.00 GB | F16 1.83 GB | **2.83 GB** | Third-party GGUF conversion, **no licence field** |

Both come from ModelScope and support HTTP `Range`, so an interrupted download resumes.
The default is the standard source not because it sounds much better, but because its
provenance is unambiguous — if a distribution ever bundles the weights, there is nothing
extra to explain.

`Acoustic` is the same file in both sets, but **`BaseLM` and `Acoustic` must come from the
same release**; mixing them produces noise.

## Performance

| | |
|---|---|
| RTF | Roughly **10–30** on a phone's CPU (a desktop 4090 gets 0.3) |
| 5 s of speech | About **1–2 minutes** |
| Memory | 3.55 GB of weights plus KV cache; **an 8 GB device is recommended** — available memory is checked before loading |
| Disk | 4 GB (weights plus cache) |

A 6 GB device may not manage it. Rather than let the system OOM-kill the process, the load
step reports "X GB available, Y GB needed".

## Building

The path is pure ASCII, so AGP accepts it and the build happens in place — no sandbox copy.

```bat
tools\build.bat :plugins:voxcpm2:assembleRelease
```

The output is unsigned:

```
plugins\voxcpm2\build\outputs\apk\release\voxcpm2-release-unsigned.apk
```

Sign it with the host's key (all three schemes v1 + v2 + v3 are enabled):

```powershell
powershell -ExecutionPolicy Bypass -File <host>\tools\sign-apk.ps1 `
  -Apk <unsigned.apk> -Out ...\Ponko-EX_VoxCPM2_release.apk
```

**Don't use `gradlew`** — the wrapper's dists are empty on this machine.
`tools\build.bat` loads the MSVC environment, points at JDK 17 and the Android SDK, and
passes `--no-daemon`.

The native library is **not** built here. `libvoxcpm2jni.so` is a prebuilt artefact
(NDK 27 + CMake, cross-compiled for arm64, statically linking `voxcpm2_runtime`, 5.77 MB
after `llvm-strip`) dropped into `plugins/voxcpm2/src/main/jniLibs/arm64-v8a/`.
To regenerate it, see [`log/2026-10-21-VoxCPM2插件线.md`](log/2026-10-21-VoxCPM2插件线.md) (Chinese).

## Known limitations

- **arm64 only** — x86 tablets cannot install it (`abiFilters` has one entry)
- **No streaming** — `req.streamAudio` is ignored; audio arrives in one piece at the end
- **One download source** — ModelScope only, no hf-mirror fallback
- **Play keeps only the latest file** — the host stores "the last thing that was
  synthesised", so synthesising again replaces it, and *Play* greys out for other plugins.
  Tap *Save* for anything worth keeping (it lands in `Music/Ponko`)
- **Long text is slow** — there is no hard character cap (the plugin never truncates), but
  phone RTF is 10–30, so 40 characters is already minutes
- **Cancellation is not instantaneous** — the native side checks between audio chunks; if it
  is stuck in LLM prefill, expect a few seconds
- **Results stay in the plugin cache** — `cache/synth/`, only the last three are kept. Use
  the host's *Save* action for anything you want to keep

## Licence

- Plugin code, JNI, docs: [MIT](LICENSE) — `Copyright (c) 2026 visitor257`
- Inference engine `llama.cpp-omni` / `ggml`: **MIT**, copyright the ggml authors; full text
  and attribution in [NOTICE.md](plugins/voxcpm2/NOTICE.md)
- VoxCPM2 weights: **Apache-2.0**, downloaded by the user, licensed with the weights and
  unaffected by this repository's licence

MIT allows commercial use, modification and closed-source distribution, on condition that
the copyright notice and permission text are retained. Hence `NOTICE.md` in the repo and a
licence section shown to users inside the plugin.

> Two more requirements that have nothing to do with open source but still apply:
> **voice rights** (China's Civil Code art. 1023 treats a person's voice like a portrait) —
> get consent before cloning a real person; and **deep-synthesis labelling** (the 2023
> Provisions on the Administration of Deep Synthesis) — generated audio published publicly
> must be identifiable. Recording yourself and listening offline involves neither.

## Layout

```
Ponko-EX/                           this branch is the project
├── README.md / README.en.md
├── LICENSE                         MIT
├── doc/                            plugin developer guide (snapshot; newest lives on main)
├── log/                            problems hit on this line
├── tools/build.bat                 build script (loads MSVC, builds in place)
└── plugins/voxcpm2/
    ├── build.gradle.kts            package name / minSdk / arm64 filter
    ├── NOTICE.md                   third-party licences and attribution (read before shipping)
    └── src/main/
        ├── AndroidManifest.xml     service and activator only, **no launcher icon**
        ├── jniLibs/arm64-v8a/      libvoxcpm2jni.so (prebuilt)
        ├── res/                    icon and strings
        └── java/com/litertchat/plugin/voxcpm2/
            ├── VoxCpm2Activator.kt      the page the host opens to enable the plugin
            ├── VoxCpm2PluginService.kt  the service: load, synthesise, settings UI, download
            ├── VoxCpm2Bridge.kt         the ten native declarations
            ├── ModelStore.kt            weight manifest and resumable download
            └── AudioUtil.kt             reference audio → 16 kHz mono WAV
```

`doc/` and `log/` are background for developing this plugin, not part of it at run time.
