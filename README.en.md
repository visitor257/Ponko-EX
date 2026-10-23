# Ponko-EX — VoxCPM2

Local text-to-speech plugin for Ponko. The model runs on the phone's CPU; apart
from downloading the weights it never touches the network and uploads nothing.

- Host app (Ponko): <https://github.com/visitor257/Ponko>
- Plugin developer's guide: [`doc/plugin-guide.en.md`](doc/plugin-guide.en.md)

[中文](README.md)

## What this is

One APK. Install it and Ponko's plugin list gains "VoxCPM2 Speech":

- Text in, WAV out. Three modes: **voice design** (describe the voice in a
  sentence), **voice clone** (5–10 s of reference audio), **verbatim clone**
  (reference audio + its transcript)
- The weights are two GGUF files (BaseLM + Acoustic) and are **not in the APK**:
  download them inside the plugin, or import your own
- CPU-only inference, roughly 1–2 minutes for 5 seconds of speech; 8 GB of RAM
  recommended

It talks to the host only through the plugin SDK (AAR) and touches none of the
host's source — as long as the plugin API doesn't change, host upgrades leave this
plugin alone.

## Download and install

**Install the host first**: Ponko 2.0.0 or newer,
<https://github.com/visitor257/Ponko/releases>. The plugin needs plugin API 12;
on an older host it installs but never binds.

**Then the plugin**: grab `Ponko-EX_VoxCPM2_0.9.0.apk` from
[Releases](https://github.com/visitor257/Ponko-EX/releases) and install it like
any other APK.

Requirements and caveats:

- Android 9+ (minSdk 28), arm64, **8 GB of RAM recommended**
- Release APKs are already signed with the **host's key** (v1 + v2 + v3). The host
  guards plugin binding with a `protectionLevel="signature"` permission, so a
  differently-signed plugin makes `bindService` throw `SecurityException` — see
  "Signing" below if you build your own
- **Model weights are not in the APK.** On first use, download a build in the
  plugin's Model tab: Standard Q8 ~3.55 GB / Lite Q4 ~2.83 GB, resumable, three
  mirrors (HuggingFace / hf-mirror / ModelScope). Or import your own two GGUF
  files. Budget the file size **plus** an in-memory copy of it
- 5 seconds of speech takes about 1–2 minutes; the plugin holds a foreground
  service while it runs, so you can switch apps

After installing: Ponko → Plugins → VoxCPM2 → prepare weights in the Model tab →
**Load model** → **Synthesize**.

Verifying:

```bat
certutil -hashfile Ponko-EX_VoxCPM2_0.9.0.apk SHA256
apksigner verify --verbose --min-sdk-version 23 Ponko-EX_VoxCPM2_0.9.0.apk
```

0.9.0 is sha256 `a44f55c8b2729990841f5f63e386e54c786648323e1e23338b8ff80c8b06a9c8`,
4,699,635 bytes.

## Features

| Tab | Contents |
|---|---|
| Model | weight status, load/unload, import two GGUF files from the device, download (version × mirror), disk usage |
| Params | voice description, CFG guidance, diffusion steps, seed, reference audio, transcript |
| Result | this synthesis with a player, plus history newest-first, each entry with its own play/save |

Also:

- **Three modes**: voice design / voice clone / verbatim clone
- **Two builds**: Standard Q8 (~3.55 GB) / Lite Q4 (~2.83 GB)
- **Download manager**: resumable, three mirrors, cancel and resume later
- **Foreground service**: surviving the background, the notification shows the stage
- **Bilingual UI**: follows the **host's** language (falls back to the system
  language when the host provides none)
- **Failures say why**: a failed load writes the reason on the engine status line
  (out of memory / no weights / native init failed / unexpected exception) instead
  of just "not loaded"

## Prerequisite: publish the plugin SDK

The plugin depends on the `com.litertchat.ponko:plugin-sdk` AAR, which the **host**
publishes into a local maven repo:

```bat
:: run inside the host's build sandbox
tools\build-android.bat :ponko-plugin-sdk:publishReleasePublicationToLocalRepoRepository
```

The artifact lands in the local maven repo (path is in the
`maven { url = ... }` block of `settings.gradle.kts`).

> ⚠ **Any change to an AIDL file or a Parcelable requires republishing**, otherwise
> the plugin is built against the old interface. The symptom is a runtime
> `NoSuchMethodError` or mis-decoded Parcel values — nasty to trace.
>
> The reverse holds too: **rebuilding only the host is useless.** The plugin
> consumes the AAR in the local maven repo, not the host's source tree.

## Building

The project path is pure ASCII, so AGP accepts it and the build runs **in place**
— no need to sync into a sandbox first.

```bat
tools\build.bat :plugins:voxcpm2:assembleRelease
```

Output: `plugins\voxcpm2\build\outputs\apk\release\voxcpm2-release-unsigned.apk`

`tools\build.bat` does three things, and the build fails without any of them:

1. Loads the MSVC environment (`vcvars64.bat`) — without it CMake can't find a build program
2. Points at JDK 17 and the Android SDK
3. Invokes gradle with `--no-daemon`

**Don't use `gradlew`**: the wrapper's dists directory is empty on this machine;
it just hangs or errors out.

> ⚠ `.bat` files must stay **pure ASCII**. cmd.exe parses them with the OEM codepage
> (GBK), so Chinese comments get mangled, break `if` blocks, and have historically
> made a script re-run itself into a 22 MB log.

## Signing (critical)

The host protects plugin binding with a `protectionLevel="signature"` permission,
so **the plugin must be signed with the same key** — otherwise `bindService` throws
`SecurityException` outright.

Use the host project's signing script (it pins v1 + v2 + v3 schemes on):

```powershell
powershell -ExecutionPolicy Bypass -File <host>\tools\sign-apk.ps1 `
  -Apk <unsigned.apk> -Out <signed.apk>
```

Keys and passwords **never enter the repo**: `.gitignore` blocks `*.jks`,
`*.keystore`, and `keystore.properties`. The script reads passwords from
`keystore.properties` or environment variables.

Verifying:

```bat
apksigner verify --verbose --min-sdk-version 23 <apk>
:: v1 / v2 / v3 should all report true
```

## Releasing

Bump `versionCode` / `versionName` in `plugins/voxcpm2/build.gradle.kts` → build →
sign → upload to a Release. Three things must agree: the commit the tag points at,
the Release asset, and the local artifact.

- Tag as `voxcpm2-<version>`, asset as `Ponko-EX_VoxCPM2_<version>.apk` — a fixed
  `release.apk` makes several versions indistinguishable in the download history
- After uploading, read the asset's `digest` back and compare it with the local
  `sha256`. **Matching byte counts prove nothing** — different builds routinely
  have identical sizes

## Code layout

```
plugins/voxcpm2/
├── build.gradle.kts                    version, dependencies, JNI packaging
└── src/main/
    ├── AndroidManifest.xml             service, permissions, ponko.actions / ponko.about
    ├── java/com/litertchat/plugin/voxcpm2/
    │   ├── VoxCpm2PluginService.kt     the plugin: UI schema, interactions, synthesis, strings
    │   ├── VoxCpm2Bridge.kt            JNI bridge (load / synthesize / free / last error)
    │   ├── ModelStore.kt               weights: versions and mirrors, download, import, checks
    │   ├── AudioUtil.kt                reference audio decode, resample, WAV wrapping
    │   └── VoxCpm2Activator.kt         headless activator, clears the stopped state
    ├── jniLibs/arm64-v8a/libvoxcpm2jni.so
    └── res/
        ├── values/                     English (the default language)
        ├── values-zh/                  Chinese
        ├── raw/about.txt               the card's About text (English)
        └── raw-zh/about.txt            same, in Chinese
```

`VoxCpm2PluginService.kt` is the main file (~1800 lines): the UI is described as a
schema (the host renders it), all strings live in `res/values*`, and logs stay in
Chinese — that's diagnostic output, and one language makes it greppable.

## Licence

[MIT](LICENSE) — the plugin code and docs.

```text
Copyright (c) 2026 visitor257
```

**Model weights are not covered by this licence.** They ship neither in this repo
nor in the APK, and each follows the licence its source declares: the official
VoxCPM2 weights are Apache-2.0, while the Lite Q4 build is a third-party
conversion whose repository declares no licence. Check before commercial use.
