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

- [x] **Persist item positions.** Scroll position (first visible item + its
      offset) is saved to the `playlist_ui` prefs in `PlaylistFragment.onPause`
      and restored on the first list emission.
- [x] **Make sorting and grouping one-off operations, not persistent modes.**
      `SortKey`/`GroupKey` lost their MANUAL/NONE members; picking a sort or
      group from the bottom-bar dialogs calls `PlaylistRepository.applySort` /
      `applyGroup`, which renumber `manualOrderIndex` once. The list is now
      *always* displayed in stored manual order. `applyManualMove` falls back to
      a full renumber when the neighbour gap is exhausted.
- [x] **Track numbers** shown per row (position in the list, 1-based).
- [x] **Draggable scrollbar** — RecyclerView's built-in fast scroller
      (`app:fastScrollEnabled` + thumb/track drawables).
- [x] **Highlight the currently playing track** — the adapter takes a
      `nowPlayingId` from the engine's state and sets `isActivated` on the row.

## 2. Effects menu (ffmpeg-powered)

Native groundwork is **done** — `libavfilter` is compiled in and the JNI surface
exists:
- `NativeBridge.nativeSetFilterGraph(handle, "superequalizer=1b=6:2b=4")` —
  installs any ffmpeg filter-graph string, or clears it with `null`.
- `NativeBridge.nativeSendFilterCommand(handle, target, cmd, arg)` — live
  parameter tweaks without rebuilding the graph (use this for slider drags).
- Both must be called on the decode thread (`PlaybackEngine`'s handler), like
  every other native call.

Kotlin/UI side is now done — see `ui/effects/EffectsFragment` (third pager page),
`data/effects/EffectsConfig` (chain → filter string) and `EffectsStore`
(SharedPreferences-backed, process-wide, read by `PlaybackEngine`):
- [x] **18-band equalizer** UI → builds `superequalizer=1b=..:...:18b=..`.
      Sliders are in dB (-12..+12); the filter takes *linear* gains (0..20,
      1 = flat), so the config converts.
- [x] Presets (flat/rock/pop/jazz/classical/bass/treble/vocal/loudness) +
      persistence of the whole chain, not just the curve.
- [x] **Playback speed** — `AudioTrack.setPlaybackParams()`, not `atempo`: byte
      counting in `PlaybackEngine` measures *source* PCM, so an in-graph tempo
      change would desync the position estimate.
- [x] **Balance** — per-channel `AudioTrack.setStereoVolume`, folded together
      with focus-loss ducking so the two don't overwrite each other.
- [x] Other free filters exposed: `bass`, `treble`, `crossfeed`, `dynaudnorm`.
      Still unused and available: `acompressor`, `alimiter`, `loudnorm`, `aecho`,
      `extrastereo`, `apulsator`, `firequalizer`, `anequalizer`, `equalizer`,
      `volume`.
- [x] Wire the effects chain so it survives track changes — `PlaybackEngine.play()`
      reinstalls the graph after each `nativeOpen`.

Note: graph edits go through `nativeSetFilterGraph` (debounced 120 ms), not
`nativeSendFilterCommand` — `superequalizer` exposes no runtime commands. The
command path is still the right one if a `bass`/`treble`/`volume`-only fast path
is ever wanted.

## 3. Full-text search

- [x] Search across title/artist/album/filename — search box in the playlist
      bottom bar, all query terms must match, filtered in the `combine()` in
      `PlaylistViewModel`. Consider Room FTS if it ever gets slow. Drag-reorder
      is disabled while a query is active (visible neighbours aren't the real
      ones).

## 4. Post-search list centering

- [x] On exiting search the list scrolls so the currently playing track is
      centered (`PlaylistFragment.consumeCenterRequest`).

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
- ~~Sort/group selection is not persisted across process death~~ — moot: they
  are one-off rewrites of the stored order now, so there is no selection to keep.
