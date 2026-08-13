# LibreAmp — project overview

Context document for handing this codebase to an assistant. It describes what the
app is, how it is put together, which decisions are deliberate (and must not be
"fixed"), and what is still missing.

## 1. What it is

An Android audio player (`dev.libreamp.player`, versionName `0.1.0`) that does all
demuxing and decoding through its own statically-linked, LGPL-only FFmpeg build
rather than through `MediaPlayer`/`ExoPlayer`/`MediaCodec`. Roughly 2,500 lines of
Kotlin plus a ~700-line JNI bridge in C.

- `minSdk 26`, `targetSdk 31`, `compileSdk 34`. ABI: **arm64-v8a only**.
- View system (no Compose), view binding, Room, coroutines/Flow. Single process,
  single Activity, three swipeable pages.
- No MediaStore anywhere: file identity is a SAF `content://` Uri or a plain
  `file://` path from the in-app filesystem picker.
- No tests, no release signing config, no ProGuard/R8 (`isMinifyEnabled = false`).

Development happens on a memory-constrained Android device, which drives
several of the constraints below.

## 2. Building

**Never run Gradle locally in this repo** — an on-device `assembleDebug` takes
~18 minutes and starves the device of memory (`build_watchdog.sh` exists to kill
the daemon tree when free memory drops below ~40 MB).

Builds go through GitHub Actions (`.github/workflows/android-build.yml`):

```
scripts/build_and_pull.sh [dest-path]
```

pushes the branch, waits for the run triggered by that SHA, downloads
`app-debug.apk` (default `./app-debug.apk`), and deletes the remote artifact.
Takes ~1.5 minutes. See `BUILD.md`.

The native library is different: it is **built manually on-device and committed**.
Gradle and CI never compile it, so CI needs no NDK.

## 3. Native layer

`app/src/main/jniLibs/arm64-v8a/libffmpeg_player.so` is a static link of a minimal
FFmpeg 7.1 plus `native/jni/ffmpeg_bridge.c`. Built with `native/build_ffmpeg.sh`
+ `native/jni/link_native.sh` (NDK r27c), requires `FFMPEG_SRC` pointing at an
extracted ffmpeg-7.1 tree and a swapfile on the build device.

What is compiled in (`--disable-everything`, then re-enabled selectively):

- **Decoders**: mp3, aac (+latm), flac, vorbis, opus, alac, wma (v1/v2/pro/
  lossless/voice), and the PCM family.
- **Demuxers**: mov, matroska, avi, asf, ogg, flac, mp3, wav, aiff, caf, aac.
- **Filters**: `superequalizer`, `equalizer`, `firequalizer`, `anequalizer`,
  `bass`, `treble`, `volume`, `crossfeed`, `acompressor`, `alimiter`,
  `dynaudnorm`, `loudnorm`, `atempo`, `aecho`, `stereotools`, `extrastereo`,
  `apulsator` (plus the plumbing filters `abuffer`/`abuffersink`/`aformat`/
  `aresample`/`anull`).
- No video decoders at all, no network/protocols, GPL and nonfree disabled.

### JNI surface (`native_bridge/NativeBridge.kt`)

`nativeOpen(fd, displayName, targetSampleRate) -> handle`, `nativeGetDurationUs`,
`nativeReadPcmChunk(handle, directBuffer, capacity)` (returns bytes written, `-1`
at true EOF, `-2` on error), `nativeSeekUs`, `nativeGetTags` (flattened k/v
array), `nativeGetEmbeddedArt` (raw JPEG/PNG bytes), `nativeClose`,
`nativeSetFilterGraph(handle, desc?)`, `nativeSendFilterCommand(handle, target,
cmd, arg)`, `nativeGetFfmpegConfig`.

**Threading contract: every native call for a given handle must be issued from the
same thread** — `PlaybackEngine`'s decode `HandlerThread`. The native side takes no
locks. The filter graph is per-`PlayerContext`, so it dies with the handle and is
rebuilt after each open and after each seek (the seek rebuild drops stale filter
tails such as echo/FIR delay).

## 4. Source map

```
data/db/         PlaylistEntryEntity, PlaylistEntryDao, AppDatabase, PlaylistRepository,
                 PlaylistSort (sort/group/search helpers)
data/effects/    EffectsConfig (+ EqPresets), EffectsStore
native_bridge/   NativeBridge
playback/        PlaybackEngine, PlaybackService, PlaybackController, PlaybackState,
                 MediaSessionHolder, AudioFocusManager
ui/nowplaying/   NowPlayingFragment
ui/playlist/     PlaylistFragment, PlaylistViewModel, PlaylistAdapter,
                 PlaylistTouchCallback, FastScrollBar
ui/filepicker/   FilePickerActivity, FileBrowserAdapter
util/            MediaFileFilter, SafFileUtils (+ MediaProbe)
MainActivity     ViewPager2 host
```

## 5. Playback

**`PlaybackEngine`** owns one dedicated decode thread, one long-lived `AudioTrack`
for the whole session (never recreated per track, for click-free transitions), and
the native handle. Output is 16-bit PCM stereo at the device's native output rate
(`AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE`, falling back to 44100); FFmpeg
resamples to that. The decode loop reposts itself on the handler, reading 16 KB
chunks into a direct `ByteBuffer` and blocking on `AudioTrack.write`.

Playback position is **estimated from bytes written** (`basePositionUs +
bytesWritten / bytesPerSecond`), not queried from the track. This is why playback
speed is applied on the `AudioTrack` and not as an `atempo` filter — an in-graph
tempo change would make output bytes stop corresponding to source time and
desync the estimate.

State is a `PlaybackState` `StateFlow` (entry, isPlaying, positionUs, durationUs,
shuffle, repeatMode, error).

**`PlaybackController`** is a process-wide singleton holding the one engine, so UI
fragments and the service share it without a bound-service/IPC layer.

**`PlaybackService`** is the foreground service: it owns queue traversal
(next/prev/shuffle/repeat), the platform `MediaSession`, and the MediaStyle
notification (channel `playback`, `IMPORTANCE_LOW`, prev/play-pause/next actions).
It observes the repository so the queue tracks the playlist, and starts
`startForeground` synchronously in `onStartCommand` to avoid the foreground-start
timeout. Actions: `ACTION_PLAY`, `ACTION_PAUSE`, `ACTION_NEXT`, `ACTION_PREV`,
`ACTION_PLAY_ENTRY` (+ `EXTRA_ENTRY_ID`). Repeat modes: OFF / ALL / ONE.

**`AudioFocusManager`** implements the full API 26 focus state machine: permanent
loss stops and abandons; transient loss auto-pauses and remembers it was
automatic; can-duck lowers volume; gain resumes **only** if the pause was
automatic, never overriding a manual user pause.

**`MediaSessionHolder`** wraps the raw platform `android.media.session.MediaSession`
(no androidx.media compat artifact — targetSdk 31 permits the platform APIs).
Album art is decoded once per art path and cached.

## 6. Data model

One Room table, `playlist_entries` (`libreamp.db`, version 3,
`fallbackToDestructiveMigration`):

```
id, contentUri, displayName, mediaType(AUDIO|VIDEO), title, artist, album,
durationMs, manualOrderIndex, dateAddedMs, lastModifiedMs, accessRevoked, artPath
```

- `manualOrderIndex` is the single source of truth for list order; rows are
  numbered in steps of 1000 so a drag-drop usually rewrites one row (average of
  neighbours), falling back to a full renumber when a gap is exhausted.
- `PlaylistRepository.refreshAccessState()` runs at startup and marks entries
  whose access disappeared (revoked SAF grant, or a `file://` path that no longer
  exists) instead of letting playback crash later.
- `MediaProbe` (in `util/SafFileUtils.kt`) reads duration/tags/cover art through
  the *same* native bridge used for playback, so metadata and playback share one
  demux path. Cover art is written to `filesDir/album_art/<uuid>.art` and deleted
  with the entry.

## 7. UI

`MainActivity` hosts a `ViewPager2` with three swipeable pages: **Now Playing**
(0), **Playlist** (1), **Effects** (2).

### Now Playing
Album art, title/subtitle, seek bar with position/duration, prev / play-pause /
next, shuffle and repeat toggles (repeat icon switches to repeat-one; both dim
when off).

### Playlist
Rows show a 1-based track number, cover art, title, `artist • album`, duration,
and a drag handle. The currently playing row is highlighted via `isActivated` on a
ripple/selector background, fed by `nowPlayingId` from the engine state
(`distinctUntilChanged`, so position ticks don't redraw the list).

The bottom bar toggles **mutually exclusive contextual modes**, with the active
button highlighted (`isActivated`, tinted `colorAccent`):

- **Add** — opens the in-app file picker.
- **Remove** (SELECTION) — checkboxes + a Delete item in the toolbar.
- **Search** — a query row under the toolbar; independent of the modes.
- **Sort** (SORTING) — reveals a "Sort by:" dropdown *above the list*, and is the
  only mode in which manual drag-reorder is enabled.
- **Group** (GROUPING) — reveals a "Group by:" dropdown above the list.

**Sorting and grouping are one-off operations, not view modes.** The dropdowns
rest on a "Choose…" placeholder; picking an entry calls
`PlaylistRepository.applySort`/`applyGroup`, which *renumber `manualOrderIndex`
once*, then the dropdown snaps back to the placeholder. The list is therefore
always displayed in stored manual order — which is why `SortKey`/`GroupKey` have
no `MANUAL`/`NONE` members. Sort keys: title, artist, album, duration, date added,
last modified, plus "Reverse current order". Group keys: artist, album, media
type (buckets ordered alphabetically, order within a bucket preserved).

Other playlist behaviour:

- **Search** matches all whitespace-separated terms against
  title + artist + album + filename, as a filter step in the ViewModel's
  `combine()`. Drag-reorder is disabled while a query is active, because the
  visible neighbours a drop resolves against would not be the real ones.
- **Closing search centers the list on the currently playing track**, rather than
  restoring the pre-search scroll offset.
- **Scroll position persists** (first visible item + offset, in the `playlist_ui`
  prefs, saved in `onPause`, restored on the first list emission).
- **`FastScrollBar`** is a custom view overlaying the list's right edge (22dp).
  RecyclerView's built-in fast scroller was tried and abandoned: it only accepts a
  drag within a strip as wide as the thumb drawable's intrinsic width, and only
  while it is in its VISIBLE state (~1.5s after the last scroll), so it cannot be
  grabbed in practice. `FastScrollBar` stays visible whenever the list overflows,
  accepts a touch anywhere across its width within the thumb's vertical span plus
  slop, calls `requestDisallowInterceptTouchEvent` so ViewPager2 can't steal the
  gesture, and **passes non-thumb touches through** to the row beneath so the drag
  handles in the same corner keep working. Drag maps to an index-proportional
  `scrollToPositionWithOffset`.

### Effects
Everything edits one `EffectsConfig`, persisted in the `effects` prefs by
`EffectsStore` (a process-wide singleton, mirroring `PlaybackController`) and
pushed to the engine.

- Master enable switch; preset dropdown (Flat, Rock, Pop, Jazz, Classical, Bass
  boost, Treble boost, Vocal, Loudness, + Custom, which any manual band edit
  switches to).
- **18-band equalizer** built on `superequalizer` (chosen because it is exactly 18
  bands): 65 Hz … 20 kHz. Sliders are in dB (−12…+12, 0.5 steps); the filter takes
  *linear* gain multipliers (0…20, 1 = flat), so `EffectsConfig` converts with
  `10^(dB/20)`. Reset button.
- **Bass / treble** (`bass=g=`, `treble=g=`, ±20 dB), **crossfeed**, **dynamic
  normalization** (`dynaudnorm`) toggles.
- **Speed** 0.25×–2.0× via `AudioTrack.setPlaybackParams` (see §5).
- **Balance** via per-channel `AudioTrack.setStereoVolume`, folded together with
  focus-loss ducking so the two never overwrite each other.

The ffmpeg half is rendered to a single filter-graph string and installed with
`nativeSetFilterGraph`, **debounced 120 ms** — not `nativeSendFilterCommand`,
because `superequalizer` exposes no runtime commands. The command path remains the
right choice if a `bass`/`treble`/`volume`-only fast path is ever wanted. The
graph is reinstalled in `PlaybackEngine.play()` after every `nativeOpen`, so it
survives track changes. When effects are disabled the graph is cleared entirely
(true passthrough), and bands at 0 dB are omitted from the string.

### File picker
`FilePickerActivity` browses the **real filesystem** (not SAF) so entries carry
plain absolute paths: an editable address bar, directory navigation, select-all,
and selection that persists across navigation. Checking a directory pulls in its
direct filtered children only — no recursive walk. `MediaFileFilter` is the
extension allow-list. Requires `MANAGE_EXTERNAL_STORAGE` (and legacy
`READ_EXTERNAL_STORAGE` up to API 32).

## 8. Deliberate decisions — do not "fix" these

- **Only the audio track of video files is played.** This is by spec; the FFmpeg
  build enables no video decoders. Not a bug.
- **Never run Gradle locally** (§2).
- **No MediaStore.** All file identity is SAF Uris or absolute paths.
- **Sort/group rewrite stored order** rather than acting as a display lens (§7).
- **Speed on the AudioTrack, not `atempo`** — position accounting depends on it (§5).
- **All native calls on the engine's decode thread** (§3).
- The long-lived `AudioTrack` is intentionally never recreated per track.

## 9. Known gaps and rough edges

Not implemented:

- Sleep timer (~30 lines in `PlaybackService`).
- Resume playback position across restarts; bookmarks.
- ReplayGain — `MediaProbe` already reads every FFmpeg tag, so
  `replaygain_track_gain` is available for free; or use the `volume` filter.
- M3U/M3U8 import/export.
- Multiple playlists — hardcoded to one; `PlaylistRepository` documents it as
  "the single playlist".
- True gapless / crossfade — needs next-track prefetch, and `acrossfade` is not
  compiled in (it takes two inputs, which does not fit the current single-source
  graph shape).
- Themes / dark mode: `themes.xml` is three hardcoded colours on a DayNight parent.
- Notification has no `setLargeIcon` (album art reaches the lock screen via
  MediaSession metadata, but not the notification itself).

Known inconsistencies:

- `MediaFileFilter` accepts `.ape`, `.mid`, `.midi`, `.flv`, `.ts`, but the FFmpeg
  build has no APE/MIDI decoder and no FLV/MPEGTS demuxer — such files can be
  added to the playlist and then fail at playback. Either drop them from the
  filter or enable the corresponding decoders/demuxers.
- `AndroidManifest.xml` carries a TODO: raising targetSdk to 34 will require
  `FOREGROUND_SERVICE_MEDIA_PLAYBACK`.
- Shuffle reshuffles whenever the queue changes; "previous" always steps back
  rather than restarting the current track first.
- The adapter uses `notifyDataSetChanged` throughout (fine at playlist scale, but
  no DiffUtil).
