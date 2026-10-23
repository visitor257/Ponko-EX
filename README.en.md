# Ponko-EX

![License](https://img.shields.io/badge/license-MIT-blue.svg) ![Release](https://img.shields.io/github/v/release/visitor257/Ponko-EX?label=plugin&color=success) ![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white) ![minSdk](https://img.shields.io/badge/minSdk-28-orange.svg) ![ABI](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey.svg) ![Host](https://img.shields.io/badge/host-Ponko%202.0.0%2B-informational) ![API](https://img.shields.io/badge/plugin%20API-v12-blueviolet)

> The plugin project for Ponko — capabilities like speech synthesis live in
> plugins instead of the main app.
>
> **Fully independent of the host [Ponko](https://github.com/visitor257/Ponko)**:
> this project depends only on the plugin SDK AAR and touches none of the host's
> own code.

English · [中文](README.md)

**Two paths — pick yours:**

| I want to | Go to |
|---|---|
| **Install a plugin and use it** (VoxCPM2 speech synthesis) | [Download and install](#download-and-install) |
| **Write my own plugin** | [Prerequisite: publish the plugin SDK](#prerequisite-publish-the-plugin-sdk) → [Building](#building) → [Signing](#signing-critical) |

- Developer's guide: [`doc/plugin-guide.en.md`](doc/plugin-guide.en.md) · Why it is designed this way: [`log/`](log/)

## Contents

- [Plugins](#plugins)
- [Download and install](#download-and-install)
- [Prerequisite: publish the plugin SDK](#prerequisite-publish-the-plugin-sdk)
- [Building](#building)
- [Signing (critical)](#signing-critical)
- [Releasing](#releasing)
- [One line, one job](#one-line-one-job)
- [Repository layout](#repository-layout)
- [Where the docs are](#where-the-docs-are)
- [Licence](#licence)

---

## Plugins

| Plugin | What it does | Branch | Latest release |
|---|---|---|---|
| **VoxCPM2 Speech** | Local text-to-speech, CPU-only inference on the phone | `Ponko-EX_VoxCPM2` | [0.9.0](https://github.com/visitor257/Ponko-EX/releases/tag/voxcpm2-0.9.0) |
| **Echo** | Does nothing — verifies the plugin pipeline and doubles as a template | `Ponko-EX_test` | — |

---

# Using

## Download and install

### 1. Install the host first

Ponko **2.0.0 or newer**: <https://github.com/visitor257/Ponko/releases>

Plugins talk to the host over its plugin SDK. Below 2.0.0 (plugin API 12) the
host **binds nothing**.

### 2. Then the plugin

Grab `Ponko-EX_VoxCPM2_0.9.0.apk` from
[Releases](https://github.com/visitor257/Ponko-EX/releases) and install it like
any other APK.

### 3. Get the weights

**Model weights are not in the APK** (far too large to bundle):

| Method | Details |
|---|---|
| Download in-app | The plugin's Model tab. Standard Q8 ~**3.55 GB** / Lite Q4 ~**2.83 GB**; resumable, three mirrors |
| Import your own | Two GGUF files: BaseLM + Acoustic |

> Budget the file size **plus** an in-memory copy of it.

### 4. Use it

Ponko → Plugins → VoxCPM2 → prepare weights in the Model tab → **Load model** → **Synthesize**

Requirements and expectations:

| Item | Value |
|---|---|
| OS | Android 9+ (minSdk 28) |
| ABI | arm64 |
| RAM | **8 GB recommended** |
| Speed | CPU-only, roughly **1–2 minutes** for 5 seconds of speech |

On signing: release APKs are already signed with the **host's key** (v1 + v2 +
v3). The host guards plugin binding with a `protectionLevel="signature"`
permission, so a differently-signed plugin makes `bindService` throw
`SecurityException` — see [Signing](#signing-critical) if you build your own.

### Verifying a download

```bat
certutil -hashfile Ponko-EX_VoxCPM2_0.9.0.apk SHA256
apksigner verify --verbose --min-sdk-version 23 Ponko-EX_VoxCPM2_0.9.0.apk
```

0.9.0 is sha256 `a44f55c8b2729990841f5f63e386e54c786648323e1e23338b8ff80c8b06a9c8`,
4,699,635 bytes.

---

# Developing

## Prerequisite: publish the plugin SDK

Plugins depend on the `com.litertchat.ponko:plugin-sdk` AAR, which the **host**
publishes into a local maven repo:

```bat
:: run inside the host's build sandbox
tools\build-android.bat :ponko-plugin-sdk:publishReleasePublicationToLocalRepoRepository
```

The artifact lands in the local maven repo (path is in the
`maven { url = ... }` block of `settings.gradle.kts`).

> ⚠ **Any change to an AIDL file or a Parcelable requires republishing**, otherwise
> plugins here are built against the old interface. The symptom is a runtime
> `NoSuchMethodError` or mis-decoded Parcel values — nasty to trace.
>
> The reverse holds too: **rebuilding only the host is useless.** Plugins consume
> the AAR in the local maven repo, not the host's source tree.

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
so **a plugin must be signed with the same key** — otherwise `bindService` throws
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

Bump the version → build → sign → upload to a Release.

Three things must agree: **the commit the tag points at, the Release asset, and
the local artifact**.

Put the version in the asset name (`Ponko-EX_VoxCPM2_0.9.0.apk`) — a fixed
`release.apk` makes several versions indistinguishable in the download history.

---

# Repository

## One line, one job

This repo splits work by **branches**. Same working tree; `git checkout` moves you
between them.

| Branch | Contents | Purpose |
|---|---|---|
| `main` | This README, `doc/`, `log/`, plus the build skeleton. **No plugin modules.** | The main line. Docs and conventions live here |
| `Ponko-EX_test` | Everything above + `plugins/echo` (the Echo test plugin) | The test line. Verifies the pipeline, doubles as a template |
| `Ponko-EX_VoxCPM2` | Everything above + `plugins/voxcpm2` (VoxCPM2 speech) | The plugin line. Releases are cut from here |

```bash
git checkout Ponko-EX_test     # go to the test line; switch here to build Echo
git checkout main              # back to the main line, docs only
```

**Why plugin modules aren't on the main line**: the main line is for docs and
conventions; each model family gets its own line (VoxCPM2 and friends). The test
plugin is scaffolding. Once it has proven the pipeline and been copied as a
template, it should step aside instead of occupying the main line forever.

> ⚠ Running a build on `main` reports "no plugin modules". That is **expected**,
> not a failure.

## Repository layout

```
Ponko-EX/
├── README.md / README.en.md        this file (Chinese / English)
├── doc/
│   ├── plugin-guide.md             plugin developer's guide
│   └── plugin-guide.en.md          same, in English
├── log/
│   ├── README.md                   what this folder is
│   └── 2026-10-21-*.md             problems hit during development (Chinese, informal)
├── settings.gradle.kts             local maven repo pointing at the plugin SDK
├── build.gradle.kts                KGP version must match the host
├── gradle.properties
├── tools/build.bat                 pure ASCII; loads the MSVC env, then builds in place
├── tools/push-via-api.py           push over the REST API when git push is blocked
└── plugins/                        present only on the plugin lines
    ├── echo/                       Ponko-EX_test
    └── voxcpm2/                    Ponko-EX_VoxCPM2
```

## Where the docs are

| I want to know | Read |
|---|---|
| How to write a plugin, what the interface looks like, how to supply UI config | [`doc/plugin-guide.en.md`](doc/plugin-guide.en.md) |
| Why something is designed this way, which traps were hit | [`log/`](log/) (Chinese only) |

The guide tells you **how**. `log/` tells you **why**. If you followed the guide
and it still doesn't work, read `log/` first — someone has probably hit it already.

## Licence

[MIT](LICENSE) — covers the code, the docs, and the sample plugins.

```text
Copyright (c) 2026 visitor257
```

In short: use it, modify it, ship it commercially, keep it closed — just keep the
copyright notice. There is **no warranty of any kind**; plugins run on your device
and drive your models, so the consequences are yours.

> The host app (Ponko) uses the same licence, but the two repositories are
> independent: the host's `LICENSE` does not cover files here, and vice versa.

**Model weights are not covered by this licence.** They ship neither in this repo
nor in the APK, and each follows the licence its source declares: the official
VoxCPM2 weights are Apache-2.0, while the Lite Q4 build is a third-party
conversion whose repository declares no licence. Check before commercial use.
