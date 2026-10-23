# Ponko-EX

![License](https://img.shields.io/badge/license-MIT-blue.svg) ![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white) ![minSdk](https://img.shields.io/badge/minSdk-28-orange.svg) ![ABI](https://img.shields.io/badge/ABI-arm64--v8a-lightgrey.svg) ![Host](https://img.shields.io/badge/host-Ponko%202.0.0%2B-informational) ![API](https://img.shields.io/badge/plugin%20API-v12-blueviolet)

> The plugin project for Ponko — capabilities like speech synthesis live in
> plugins instead of the main app.
>
> **Fully independent of the host [Ponko](https://github.com/visitor257/Ponko)**:
> this project depends only on the plugin SDK AAR and touches none of the host's
> own code.

English · [中文](README.md)

**This repository holds docs and conventions only — no install instructions.**
Obtaining and installing a plugin is each plugin line's own business; what lives
here is how to write one, what the interface looks like, and why it is shaped
that way.

- Developer's guide: [`doc/plugin-guide.en.md`](doc/plugin-guide.en.md)
- Design notes and traps hit along the way: [`log/`](log/)

## Contents

- [Plugins](#plugins)
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

| Plugin | What it does | Branch | Version |
|---|---|---|---|
| **VoxCPM2 Speech** | Local text-to-speech, CPU-only inference on the phone | `Ponko-EX_VoxCPM2` | `0.9.0` |
| **Echo** | Does nothing — verifies the plugin pipeline and doubles as a template | `Ponko-EX_test` | — |

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
