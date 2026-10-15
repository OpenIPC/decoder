# Testing and releases

- [Branches](#branches)
- [CI](#ci)
- [Unit tests](#unit-tests)
- [Versions and releases](#versions-and-releases)
- [Testing a branch build](#testing-a-branch-build)
- [Stack traces and mapping.txt](#stack-traces-and-mappingtxt)
- [Building locally](#building-locally)
- [Device tests](#device-tests)

## Branches

| Branch | Status |
|---|---|
| `v2` | **Main development branch** (default). Modular code in `android/`. Built as `decoder-v2.apk` |
| `basic` | Legacy single-file version (1.0x), kept for reference and older setups. Built as `decoder.apk`. Its version history is in the [`basic` README](https://github.com/OpenIPC/decoder/tree/basic#version-history) |

Base pull requests on `v2`.

## CI

`.github/workflows/build.yml` runs on every push and pull request, and by hand (`workflow_dispatch`, optionally for a given commit). A newer push to the same branch cancels the older run, so `latest` always gets the build of the newest commit.

| Job | Runs on | What it does |
|---|---|---|
| `test` | every push and pull request, forks included (no secrets) | `./gradlew testDebugUnitTest`, then `./gradlew lintDebug` against `app/lint-baseline.xml` (only new findings fail). Reports are uploaded when it fails |
| `build` | pushes and manual runs, not pull requests | signed `assembleRelease` (R8 minify and resource shrinking), the R8 `mapping.txt` kept as a workflow artifact |

What a `build` run publishes:

| Branch | Output |
|---|---|
| `v2` | `decoder-v2.apk` and `decoder-v2-mapping.txt` in the [`latest`](https://github.com/OpenIPC/decoder/releases/tag/latest) release; a [versioned release](#versions-and-releases) for a new version; a message with the APK to the OpenIPC Telegram channel |
| `basic` | `decoder.apk` and its mapping in `latest`; Telegram |
| any other | workflow artifacts `decoder-<branch, / → ->` (APK) and `…-mapping` on the run page |

A pull request therefore gets tests and lint only; its APK comes from the push run of its branch.

## Unit tests

JVM tests under `android/app/src/test/java/com/openipc/decoder/` (JUnit 4, Android stubs return defaults). They cover the parts that are pure logic: RTSP responses, URLs, authentication and sessions against a fake server (`RtspSession…`, `RtspAuth…`, `RtspUrl…`), SDP and transports, RTP reordering and `udp://` URLs, NAL and JPEG assembly, HTTP MJPEG parsing, audio formats and decoding, the decode planner, the credential store, the debug server (JSON, request parsing, redaction), stream checks, the grid layout, video fit and the buffer pool. No test needs a device; UI and MediaCodec behaviour are tested on the bench ([Device tests](#device-tests)).

CI is the reference: the project does not depend on local builds.

## Versions and releases

- `v2` is **2.xx** (from 2.00). Every pull request that changes the app bumps `android/app/build.gradle` as its last commit before the merge: `versionName` +0.01, `versionCode` +1. Docs-only and CI-only pull requests do not. Current: **2.10** (code 35).
- `basic` keeps its 1.07.x line.
- The first `v2` build of a new `versionName` creates the release **`v<version>`** at the commit it built, with `decoder-v2-<version>.apk`, `mapping-<version>.txt` and notes generated from the merged pull requests since the previous `v2.*` release (by version number). It is not marked *Latest*: the README links `releases/tag/latest`.
- A push without a version bump updates only `latest`. A later build of the tagged commit adds a missing APK or mapping to an existing release; a build of another commit does not touch it.
- The installed version and build hash show in the menu (under **Exit**, `v2.10, <hash>`) and in `/stats.json` (`version`, `git`).
- Every build is `com.openipc.decoder` with the release signature, so a branch build installs over a release and keeps the settings.

Release notes are the change history: [releases page](https://github.com/OpenIPC/decoder/releases).

## Testing a branch build

```sh
B=feat/my-branch
RUN=$(gh run list -R OpenIPC/decoder -b "$B" -e push -L 1 --json databaseId -q '.[0].databaseId')
gh run download "$RUN" -R OpenIPC/decoder -n "decoder-${B//\//-}"
adb install -r "decoder-${B//\//-}.apk"
```

A given version: `gh release download v2.10 -R OpenIPC/decoder` (`decoder-v2-2.10.apk`). Check the hash under **Exit** after installing.

## Stack traces and mapping.txt

Release builds are obfuscated by R8. To read a stack trace from a crash screen or a log, use the mapping of the same build: `mapping-<version>.txt` from the versioned release, `decoder-v2-mapping.txt` from `latest` (only for the build that is there now), or the `…-mapping` artifact of a branch run. Then, with the Android SDK command-line tools:

```sh
retrace mapping-2.10.txt stacktrace.txt
```

## Building locally

Not needed for contributing (CI builds every push), but possible: JDK 17 and the Android SDK (platform 36), with `ANDROID_HOME` or `android/local.properties`.

```bash
cd android
./gradlew testDebugUnitTest    # unit tests
./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk, no keys needed
```

A release build needs a keystore at `android/key.jks` and `KEYSTORE_PASS`, `KEY_ALIAS`, `KEY_PASS` in the environment (`./gradlew assembleRelease`). Debug and release builds are signed with different keys: to switch, uninstall first, which removes the saved camera URLs.

## Device tests

The bench (devices, cameras, addresses, adb access, scripts, log lines, pitfalls) is described in [LABORATORY.md](../LABORATORY.md). Test rounds run as GitHub issues that follow the structure of [#53](https://github.com/OpenIPC/decoder/issues/53). Reports from users: [devices.md](devices.md#reporting).
