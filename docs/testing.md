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
| `v2` | `decoder-v2.apk` and `decoder-v2-mapping.txt` in the [`latest`](https://github.com/OpenIPC/decoder/releases/tag/latest) release (replaced by every build); a message with the APK, version and commit to the OpenIPC Telegram channel |
| `basic` | `decoder.apk` and its mapping in `latest`; Telegram |
| any other | workflow artifacts `decoder-<branch, / → ->` (APK) and `…-mapping` on the run page |

Every run, `v2` and `basic` included, also keeps its mapping as the artifact `…-mapping`. Artifacts expire after 90 days.

A pull request therefore gets tests and lint only; its APK comes from the push run of its branch.

## Unit tests

JVM tests under `android/app/src/test/java/com/openipc/decoder/` (JUnit 4, Android stubs return defaults). They cover the parts that are pure logic: RTSP responses, URLs, authentication and sessions against a fake server (`RtspSession…`, `RtspAuth…`, `RtspUrl…`), SDP and transports, RTP reordering and `udp://` URLs, NAL and JPEG assembly, HTTP MJPEG parsing, audio formats and decoding, the decode planner, the credential store, the debug server (JSON, request parsing, redaction), stream checks, the grid layout, video fit and the buffer pool. No test needs a device; UI and MediaCodec behaviour are tested on the bench ([Device tests](#device-tests)).

CI is the reference: the project does not depend on local builds.

## Versions and releases

- `v2` is **2.xx** (from 2.00). Every pull request that changes the app bumps `android/app/build.gradle` as its last commit before the merge: `versionName` +0.01, `versionCode` +1. Docs-only and CI-only pull requests do not.
- `basic` keeps its 1.07.x line.
- Development is continuous, so there is **no release per version** (#194). The [`latest`](https://github.com/OpenIPC/decoder/releases/tag/latest) release always holds the newest `v2` and `basic` builds. Every `v2` and `basic` build is also posted to the OpenIPC Telegram channel with its version and commit, and that is where their older builds can be found. Builds of other branches are only workflow artifacts of their runs (90 days).
- **Test pre-releases**: to let a reporter test a branch, a pre-release named after the issue is made by hand from the branch's CI artifact, e.g. [`toys-187-test2`](https://github.com/OpenIPC/decoder/releases/tag/toys-187-test2) for #187. It is deleted when the test is over, or replaced by the next one.

  ```sh
  B=feat/my-branch                       # the branch to test
  RUN=$(gh run list -R OpenIPC/decoder -b "$B" -e push -w Build -L 1 --json databaseId -q '.[0].databaseId')
  gh run download "$RUN" -R OpenIPC/decoder -n "decoder-${B//\//-}"   # gives decoder-feat-my-branch.apk
  cp "decoder-${B//\//-}.apk" decoder-my-test1.apk                    # the asset name the reporter sees
  gh release create my-187-test1 decoder-my-test1.apk --prerelease \
    --target "$(gh run view "$RUN" -R OpenIPC/decoder --json headSha -q .headSha)" \
    --title "… test build 1 (#187)" --notes "…"
  ```

  The tag (`my-187-test1`) and the asset name are free to choose; the artifact is always named after the branch.

- The change history is the [merged pull requests](https://github.com/OpenIPC/decoder/pulls?q=is%3Apr+is%3Amerged+base%3Av2) and the [commits of `v2`](https://github.com/OpenIPC/decoder/commits/v2).
- The installed version and build hash show in the menu (under **Exit**, `v2.10, <hash>`) and in `/stats.json` (`version`, `git`).
- Every build is `com.openipc.decoder` with the release signature, so a branch build installs over a release and keeps the settings.

## Testing a branch build

```sh
B=feat/my-branch
RUN=$(gh run list -R OpenIPC/decoder -b "$B" -e push -L 1 --json databaseId -q '.[0].databaseId')
gh run download "$RUN" -R OpenIPC/decoder -n "decoder-${B//\//-}"
adb install -r "decoder-${B//\//-}.apk"
```

The current `v2` build: `gh release download latest -R OpenIPC/decoder -p decoder-v2.apk`. Check the hash under **Exit** after installing.

## Stack traces and mapping.txt

Release builds are shrunk and obfuscated by R8: classes, fields and methods get short names, and line numbers survive only through a map. A crash from a release build reads like this (an example):

```
java.lang.IllegalStateException: …
    at a.u.a(SourceFile:12)
    at com.openipc.decoder.Decoder.u(SourceFile:48)
```

R8 writes that map as **`mapping.txt`**, one per build:

```
# compiler: R8
# compiler_version: 8.13.17
# min_api: 21
# pg_map_id: 8df440e8…            ← ties the file to exactly one build
com.openipc.decoder.DecodePlanner -> a.u:
# {"id":"sourceFile","fileName":"DecodePlanner.java"}
    long capacity -> a
    long proven -> c
    long failedLoad -> d
    1:5:void <init>(long,boolean,long,long):147:147 -> <init>
```

For every class, field and method it gives `original -> obfuscated`, with the line ranges that map back to the source. It also covers the library classes that went into the APK, which is why it is about 9,000 lines (700 KB). It holds no secrets (the code is open), and devices don't need it: it only matters for reading crashes.

A mapping fits **only the build it came from**. Use the one whose build matches the hash under **Exit** or `git` in `/stats.json`:

| Build | Mapping |
|---|---|
| the current `v2` build | `decoder-v2-mapping.txt` in [`latest`](https://github.com/OpenIPC/decoder/releases/tag/latest) |
| an older `v2` build, or any branch build | the artifact `decoder-v2-mapping` (or `decoder-<branch>-mapping`) of the CI run that built it, kept 90 days. A push run is found by its commit (below). A manual run (*Run workflow* with `commit_hash`) is listed under the branch it was started on, not under the commit it built: `gh run list -R OpenIPC/decoder -w Build -e workflow_dispatch -b <branch>`, then pick the run by its time or ID |
| builds before 2.08 | none: they were made before the mapping was kept (#181) |

```sh
RUN=$(gh run list -R OpenIPC/decoder -c <full commit hash> -e push -w Build -L 1 --json databaseId -q '.[0].databaseId')
gh run download "$RUN" -R OpenIPC/decoder -n decoder-v2-mapping
retrace decoder-v2-mapping.txt stacktrace.txt     # Android SDK command-line tools
```

After `retrace`, the frames read `at com.openipc.decoder.DecodePlanner.step(DecodePlanner.java:215)`.

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
