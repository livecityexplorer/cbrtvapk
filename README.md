# IPTV Player (player-only)

A media player for **your own** IPTV sources (M3U, Xtream Codes, Stalker/Ministra). It ships with **no channels or content**.
Android TV + phone/tablet, Kotlin, Jetpack Compose (Material 3 + TV Material), Hilt, Room, Paging 3, Media3/ExoPlayer, DataStore, WorkManager.

## Build

Requirements: JDK 17, Android SDK (platform 34), Gradle 8.9+.

```bash
gradle wrapper --gradle-version 8.9     # once, to create ./gradlew
./gradlew testDebugUnitTest             # unit tests
./gradlew assembleDebug                 # -> app/build/outputs/apk/debug/app-debug.apk
```

No local Android setup? Push the project to GitHub: `.github/workflows/build-apk.yml` builds the APK and uploads it as a
workflow artifact (Actions tab > latest run > *IptvPlayer-debug-apk*).

Release builds (`assembleRelease`) are minified; sign them with your own keystore. Nothing secret is hard-coded.

## Project structure

| Module | Contents |
|---|---|
| `app` | `MainActivity` (PiP, reminders intent), `IptvApp`, navigation, theme, Home, disclaimer splash, PIN guard on players |
| `core:domain` | Models, repository interfaces, pure-Kotlin utils (PIN hashing, adult filter, EPG matching, resume policy, …) + tests |
| `core:data` | Room DB/DAOs, M3U & XMLTV parsers, Xtream & Stalker clients, sync, recording engine, DataStore settings, parental gate, backup + tests |
| `core:ui` | Shared composables (posters, channel logo, PIN keypad dialog) |
| `feature:livetv / epg / vod / series / search / recordings` | One screen set each |
| `feature:player` | ExoPlayer engine, live + VOD player, PiP, Multi-View, subtitles |
| `feature:settings` | Settings, playlist management, parental locks |

## Adding a playlist

Settings > Playlists > Add playlist, then choose a type:

* **M3U** – a URL (or a local file via the system picker). Optional XMLTV guide URLs.
* **Xtream Codes** – server URL, username, password.
* **Stalker** – portal URL and MAC address.

Use *Test connection* before saving; the app then loads channels, movies and series.

## Features by area

* Live TV with categories, favorites, recently watched, reordering, hide/lock categories.
* EPG grid, programme details, reminders, catch-up where the provider supports it.
* Player: custom controls, reconnection with backoff, PiP, Multi-View (up to 4), audio/subtitle tracks, aspect modes, external subtitles.
* Movies & series with resume, next-episode auto-play. Global search (debounced; channels, movies, series, EPG).
* DVR: schedule/record, list, play back, delete, global REC indicator.
* **Parental controls** – PIN (salted + iterated SHA-256 hash in DataStore, never the PIN itself); lock categories (live, movies, series) or single channels; the PIN is asked before playback and stays valid for 15 minutes or until *Lock now*; *Hide adult categories* toggle (name-based heuristic, also applied to newly synced categories).
* **Settings** – playlists, EPG auto-refresh + interval (drives the WorkManager job), buffer size, HW/SW decoder, default aspect ratio, subtitle size/colour, Dark / AMOLED theme, backup/restore, About + legal notice.
* **Backup/restore** – JSON via the Storage Access Framework (settings + playlists; duplicates skipped on import). The file contains playlist credentials in plain text, so keep it private; the PIN is never exported.
* First launch shows the legal notice; it must be accepted.

## Tests

`core/domain` and `core/data` contain unit tests for: M3U parser, XMLTV parser/importer, Xtream URL builder/client, Stalker, catch-up, HLS recorder, PIN hasher + adult filter, settings mapping, backup round-trip, and more.

## Known limits

* Parental locks are enforced at playback (live, VOD, zapping). Locked titles can still appear in Search/Home lists; they cannot be played without the PIN. Multi-View panes do not check locks.
* "Hide adult categories" relies on category names; providers have no standard adult flag.
* `usesCleartextTraffic` is enabled because many IPTV servers are plain HTTP.
