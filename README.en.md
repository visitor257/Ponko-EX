# Ponko-EX · VoxCPM2

A speech-synthesis plugin for [Ponko](https://github.com/visitor257/Ponko):
[VoxCPM2](https://modelscope.cn/models/OpenBMB/VoxCPM2) (OpenBMB, 2B) running **offline** on the phone.

[中文](README.md)

**A 4.6 MB plugin plus 2.8–3.6 GB of models (downloaded or imported on first run) — a TTS with no network.**
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
3. On the **Models** tab: pick a version (standard Q8 3.55 GB / compact Q4 2.83 GB) and tap a
   mirror to download (resumable, **use Wi-Fi**) — **or** import the two `.gguf` files from
   local storage (one slot for the backbone, one for the acoustic model)
4. Tap **Load model** (roughly 10–30 s); once the engine row reads *ready*, synthesise

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
> The **Results** tab carries a **transport bar** (SDK v4 `UiType.AUDIO`). It drives the
> **same player** as the Play button above it (the host's own), but you can see the time and
> drag the position — the only way to get to one sentence in a one-to-two-minute clip.
>
> The action row is drawn by the host and sits **above** the three tabs, because only the host
> can start a synthesis: the audio has to travel back through its `ResultBundle` channel to
> become playable and saveable. Anything the plugin pushed on its own would be dropped by the
> request gate.
>
> The plugin names those buttons (SDK v3 `buildActionLabels()`). The host's defaults are
> *Load engine / Unload engine / Synthesise / Play / Stop / Save*; this plugin overrides two
> of them — it loads a model, not an engine, and unloading leaves the file on disk (hence
> "release"). Play / Stop / Save keep the host defaults.

> **The host has to know v6.** This build is compiled against SDK v6 (three tabs + log pane +
> transport bar + model slots), so it needs a host that carries the 2026-10-22 SDK batch.
> Anything older splits two ways:
>
> - v3 or earlier (the build that still rejected anything outside a version range): refuses
>   to bind — the gate rejects it before it can hand over a schema, so there is no fallback.
> - v4/v5: connects and the page mostly works, but `MODEL_SLOT` (the two import rows) is
>   unknown, so it degrades into "unknown control" text blocks and **importing a local file
>   is not available** — download only. Update Ponko to use imports.
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
| Seed | empty (random) | integer | Pin it to reproduce a delivery; the Results tab shows the seed actually used |
| Threads | empty (follow Ponko) | 1–8 | Takes effect on the **next** engine load |
| Voice design description | empty | — | Equivalent to the host's `voiceDescription`; if both are empty no prefix is added |

Seed and threads exist on both sides: **a value typed in the plugin page wins** over the
host's; left empty, the host's applies.

The page is split into **three tabs** (SDK v5); being the outermost group, they can be
swiped left and right. Switching is pure visibility, never a rebuild, so typed text and
scroll position survive a switch, and the tab you were on is remembered:

| Tab | Contents |
|---|---|
| Models | Model / engine / storage status, load and release, **local import** (two slots), download (version, per-file state, mirror buttons, progress, cancel, delete) |
| Parameters | Voice design / CFG / steps / seed / threads, inference progress, last-run parameters, collapsible diagnostics, run log |
| Results | Last run's seed / parameters / text / transport bar; inference history (this session only, at most 5 entries, metadata only) |

**Target text, synthesis mode, reference audio and transcript are not on the plugin page** —
they live in the host's *functional* block at the top. That block is the caller: those four
describe *how this one call is dispatched*, and the plugin only receives the resulting
`SynthesisRequest`. A text field in the plugin page that changed nothing would be worse than
no field at all.

The tab you were on is remembered: a full repaint (load finished / synthesis started /
synthesis finished) does not bounce you back to the first tab.

The log is the host's `UiType.LOG` (SDK v4): fixed 160 dp tall, scrolling internally,
long-press to copy, last 120 lines only. New lines arrive as **incremental patches**
(`notifyUiPatch`) rather than a full page rebuild — so the log keeps its scroll position
and the *voice design* textarea you are typing into keeps its focus and content.
Scrolling back through history is safe too: new lines don't yank you to the bottom (the
host only follows the tail when the pane was already there).
Download and inference progress work the same way: they only touch their progress bar.

## The three modes

| Mode | Needs | Good for |
|---|---|---|
| Plain TTS (0) | text | General reading, the default |
| Clone (1) | reference audio | A particular timbre, arbitrary content |
| Continuation (2) | reference audio + transcript | Reproducing a performance: rhythm, pauses, tone |

**Choosing reference audio**: 5–10 seconds, one speaker, no music, no reverb. wav / mp3 /
m4a / flac all work (decoded through `MediaCodec`, then resampled to 16 kHz).

## Model files

Not in the APK — 2.8–3.6 GB is not shippable. Two ways in:

**Local import** (SDK v6): the Models tab has two slots — backbone BaseLM and acoustic —
each with its own *Import* button. The **host** opens the system file picker and forwards a
read-only FD; the plugin copies the file into its own private directory
(`files/imported/`). The picker cannot come from the plugin: it is a bound Service, has no
`startActivityForResult`, and Android 10+ forbids it from starting activities in the
background. **Imported files take priority over downloaded ones.**

**Download**, into
`Android/data/com.litertchat.plugin.voxcpm2/files/models/`:

| Version | BaseLM | Acoustic | Total | Notes |
|---|---|---|---|---|
| **Standard Q8** (default) | Q8_0 1.73 GB | F16 1.83 GB | **3.55 GB** | Repository declares `apache-2.0`; slightly better quality |
| Compact Q4 | Q4_K_M 1.00 GB | F16 1.83 GB | **2.83 GB** | Third-party GGUF conversion, **no licence field**; **this is the set the standalone test build used** |

Mirrors are listed per version, and only combinations that actually exist:

| Version | hf-mirror | HuggingFace | ModelScope |
|---|---|---|---|
| Compact Q4 | yes — `tc-mb/MiniCPM-V-Apps-gguf` | yes — same repo (usually unreachable from mainland China) | yes — `zerg13/MiniCPM-V-Apps-gguf` |
| Standard Q8 | no | no | yes — `DennisHuang/VoxCPM2-GGUF` |

The Q8_0 set has **no repository on HuggingFace** (hf-mirror 404s on that path, and it only
proxies what really exists on HF), so the standard version is ModelScope-only. Showing a
"Download from HuggingFace" button that 404s would be worse than not showing it.

All of them use HTTP `Range`, so an interrupted download resumes from where it stopped.
The default is the standard version not because it sounds much better, but because its
provenance is unambiguous — if a distribution ever bundles the models, there is nothing
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
- **The standard version has one download source** — ModelScope only; HuggingFace has no
  such repository (see the table above)
- **Play keeps only the latest file** — the host stores "the last thing that was
  synthesised", so synthesising again replaces it, and *Play* greys out for other plugins.
  Tap *Save* for anything worth keeping (it lands in `Music/Ponko`)
- **History entries cannot be played or saved individually** — the host keeps one player
  and one *Save* action, both bound to "the latest run". So the Results tab's history holds
  seed / parameters / the first 40 characters only, and only for this session
- **Diagnostics cannot start collapsed** — the host's `GROUP_START` has no "initially
  collapsed" field; groups always open
- **The inference progress bar is an estimate** — the native side only reports how many
  seconds of audio exist, not a total step count, so the total is estimated from character
  count (about 4.5 characters per second of Chinese speech)
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
