# Build notes

## CI instead of on-device builds

Local `assembleDebug` builds on this device take ~18 minutes even though the
app is small (two intents, a foreground playback service, and a prebuilt
ffmpeg-based `.so`). The device is memory- and CPU-constrained (see
`build_watchdog.sh`, which kills the Gradle daemon tree if available memory
drops below ~40MB), and Gradle runs with `--no-daemon --max-workers=1` and no
parallelism to survive on it.

Because of that, CI (GitHub Actions, `.github/workflows/android-build.yml`)
is now the primary way builds get produced and verified. It runs
`assembleDebug` on a full GitHub-hosted runner and uploads the resulting APK
as a build artifact (retention: 1 day). Local builds on-device are still fine
for quick iteration, just don't expect them to be fast.

## Native ffmpeg library

`app/src/main/jniLibs/arm64-v8a/libffmpeg_player.so` is a prebuilt static
link of a minimal LGPL-only ffmpeg (audio decode only) plus
`native/jni/ffmpeg_bridge.c`. It is built manually via
`native/build_ffmpeg.sh` + `native/jni/link_native.sh` (NDK r27c) and checked
into the repo — Gradle does not compile it, so CI does not need the NDK.
