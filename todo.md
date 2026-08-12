# LibreAmp TODO

Working notes so context can be cleared between sessions. Items are roughly in
priority order; nothing here is started unless marked otherwise.

## Ground rules for this repo

- **Never run Gradle locally.** See `BUILD.md`. Builds go through CI:
  `scripts/build_and_pull.sh` pushes, runs the GitHub Actions build, pulls
  `app-debug.apk`, and deletes the remote artifact. Takes ~1.5 min.
- Native (`libffmpeg_player.so`) is different: it is built manually on-device
  with `native/build_ffmpeg.sh` + `native/jni/link_native.sh` (NDK r27c) and the
  resulting `.so` is committed. Gradle/CI never compile it. Requires
  `FFMPEG_SRC` pointing at an extracted ffmpeg-7.1 tree, and a swapfile — the
  device has 964 MB RAM and will OOM without one.
- Playing **only the audio track of video files is intentional**, by spec. The
  ffmpeg build enables no video decoders. Not a bug, do not "fix" it.

## 1. Playlist rework

- [ ] **Persist item positions.** Manual order already persists via
      `manualOrderIndex`, but scroll position / list state does not.
- [ ] **Make sorting and grouping one-off operations, not persistent modes.**
      Currently `PlaylistViewModel.sort`/`group` are sticky `StateFlow`s that
      continuously re-derive the visible list. Change so that picking a sort
      *rewrites* `manualOrderIndex` once and then returns to manual order —
      i.e. sort/group become actions applied to the stored order, not a lens
      the list is permanently viewed through.
- [ ] **Track numbers** shown per row.
- [ ] **Draggable scrollbar** (fast-scroll thumb) on the playlist.
- [ ] **Highlight the currently playing track** in the list.

## 2. Effects menu (ffmpeg-powered)

Native groundwork is **done** — `libavfilter` is compiled in and the JNI surface
exists:
- `NativeBridge.nativeSetFilterGraph(handle, "superequalizer=1b=6:2b=4")` —
  installs any ffmpeg filter-graph string, or clears it with `null`.
- `NativeBridge.nativeSendFilterCommand(handle, target, cmd, arg)` — live
  parameter tweaks without rebuilding the graph (use this for slider drags).
- Both must be called on the decode thread (`PlaybackEngine`'s handler), like
  every other native call.

Remaining work is all Kotlin/UI:
- [ ] **18-band equalizer** UI → build a `superequalizer=1b=..:2b=..:...:18b=..`
      string. (`superequalizer` is exactly 18 bands, which is why it was chosen.)
- [ ] Presets (flat/rock/jazz/bass/etc.) + persistence of the chosen curve.
- [ ] **Playback speed** — either `atempo` in the graph (pitch-preserving) or
      `AudioTrack.setPlaybackParams()`. Latter is simpler; former is consistent
      across devices.
- [ ] **Balance** — `stereotools` filter, or per-channel `AudioTrack.setVolume`.
- [ ] Other filters already compiled in and available for free: `bass`, `treble`,
      `crossfeed`, `acompressor`, `alimiter`, `dynaudnorm`, `loudnorm`, `aecho`,
      `extrastereo`, `apulsator`, `firequalizer`, `anequalizer`, `equalizer`,
      `volume`.
- [ ] Wire the effects chain so it survives track changes — the graph is
      per-`PlayerContext`, so it must be re-applied in `PlaybackEngine.play()`
      after each `nativeOpen`.

## 3. Full-text search

- [ ] Search across title/artist/album/filename. No search exists anywhere in
      the app today. Simplest version: a filter step in the `combine()` in
      `PlaylistViewModel`; consider Room FTS if it gets slow.

## 4. Post-search list centering

- [ ] On exiting search, scroll the list so the **currently playing track is
      centered**, rather than restoring the pre-search scroll offset.

## Backlog (from the AIMP feature comparison, not yet prioritized)

- Sleep timer (~30 lines in `PlaybackService`).
- Resume playback position across restarts / bookmarks.
- ReplayGain — `MediaProbe` already reads all ffmpeg tags, so
  `replaygain_track_gain` is available for free; or use the `volume` filter.
- M3U/M3U8 playlist import/export.
- Multiple playlists (currently hardcoded to one; `PlaylistRepository` documents
  it as "the single playlist").
- True gapless / crossfade — needs next-track prefetch; `acrossfade` is not
  compiled in (it needs two inputs, so it does not fit the current single-source
  graph shape).
- Themes / dark mode (`themes.xml` is 3 hardcoded colors on a DayNight parent).
- Notification is missing `setLargeIcon` (album art shows on lock screen via
  MediaSession metadata, but not in the notification itself).

## Known inconsistencies worth a decision

- `MediaFileFilter` accepts `.ape`, `.mid`, `.midi`, `.flv`, `.ts`, but the
  ffmpeg build has no APE/MIDI decoder and no FLV/MPEGTS demuxer — those files
  can be added to the playlist and then fail at playback. Either drop them from
  the filter or enable the corresponding decoders/demuxers.
- Sort/group selection is not persisted across process death (in-memory
  `MutableStateFlow`). May become moot once item 1 makes them one-off actions.
