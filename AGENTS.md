# Repository Notes

## Project Roots
- This repo has three separate Gradle roots; run each Gradle wrapper from its own directory, not from the repository root.
- `source/` is the main Android app tracked by CI. It is a multi-module Gradle Kotlin DSL project with `:app`, `:core:*`, `:feature:*`, and `:native` modules.
- `source-next/` is a separate rewrite/prototype app with only `:app`, `:hiddenapi`, and `:native`. Keep its existing `source-next/AGENTS.md` guidance in mind for work under that directory.
- `dex/` builds the script-side dex utilities described in `dex/README.md`; its Gradle root contains `:app` and `:hiddenapi`.

## Commands
- Main app verification from `source/`: `./gradlew lint test` matches the lint CI workflow.
- Main app build from `source/`: `./gradlew assembleDebug`; release CI first runs `./gradlew check -p build-logic` before release assemble tasks.
- Main app focused test from `source/`: use module tasks such as `./gradlew :core:model:testDebugUnitTest` or `./gradlew :core:data:connectedDebugAndroidTest`.
- `source-next/` verification uses its own wrapper, for example `./gradlew test`, `./gradlew lint`, or `./gradlew connectedAndroidTest` from `source-next/`.
- `dex/` verification/build uses its own wrapper from `dex/`, for example `./gradlew test` or `./gradlew assembleDebug`.
- Built-in native binary artifacts are built by CI with `bash -e -x build/build_bin.sh all all`; the script says it was tested on Ubuntu 22.04.1 WSL2 and expects Android NDK r25c unless `NDK` is already set.

## Build Gotchas
- CI workflows for normal app changes only watch `source/**`; changes under `source-next/` or `dex/` need explicit local verification.
- `source/app` release signing reads `STORE_FILE`, `STORE_PASSWORD`, `KEY_ALIAS`, and `KEY_PASSWORD`; debug builds do not need these secrets.
- Native builds depend on git submodules under `source/native/src/main/jni/external/tar/tar` and `source-next/native/src/main/jni/external/{tar/tar,zstd/zstd-jni,rustic/rustic_core}`.
- `source-next/native` reads `src/main/jni/external/zstd/zstd-jni/version` during Gradle configuration, so missing submodules can fail before compilation.

## Architecture Cues
- `source/app/src/main/kotlin/com/xayah/databackup/SplashActivity.kt` decides first-run/update setup vs main app launch; `MainActivity.kt` wires the Compose navigation graph.
- `source` uses Hilt and Hilt WorkManager integration; `source-next` uses Koin and app-local repositories/view models.
- `source/build-logic` defines convention plugins used by most `source` modules; check it before changing SDK, JVM target, Compose, Hilt, Room, protobuf, or test setup.
- `source/core/database/schemas/` contains Room schema JSON history; preserve/update schemas when changing database entities or migrations.

## Testing Notes
- `source/core:data` instrumentation tests call `Shell.cmd("su ...")`; root/device assumptions may make them unsuitable for routine host-only verification.
- `source-next/native` has an instrumentation test that loads `librustic`; run it only with a device/emulator and initialized native submodules.
