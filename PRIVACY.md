# Privacy and permissions

Foldman has no accounts, analytics, ads or servers of its own. Everything it knows stays on your phone, apart from the requests to Apple described below.

## Permissions

| Permission | Why Foldman asks |
| --- | --- |
| **Notification access** (Settings → Notification access) | Android only lets apps with notification access see other apps' *media sessions*: what YT Music, Spotify or Apple Music is playing, and their play/pause/skip controls. Foldman's listener does nothing with notifications themselves; it never reads, stores or dismisses them. Only needed for the YT Music and Spotify sources, and for Apple Music without MusicKit. |
| **Folder access** (the system folder picker) | Read access to the one music folder you choose, and nothing else on the phone. |
| **Notifications** | Shows the media notification while local music or web radio plays, with play/pause and skip on the lock screen. |
| **Foreground service (media playback)** | Keeps local music and web radio playing when Foldman is in the background. |
| **Internet** | Downloads album art for streaming sources, plays web radio, and talks to Apple and Radio Browser (below). |
| Wake lock, network state | Requested by Apple's MusicKit player, for streaming. |

## What's stored on the phone

Foldman keeps a few settings in its private app storage:
- the source in the deck;
- the music folder you picked;
- the CLICK and HAPTIC switch positions;
- your saved web radio stations and the last one you listened to;
- your Apple Music **user token**, after you sign in, and a developer token if you pasted one in.

None of it leaves the phone. Foldman opts out of Android's cloud backup and of device-to-device transfer, so your Apple Music token isn't copied into a backup or onto a new phone; on a new phone you sign in again. Uninstalling Foldman deletes it all.

> Versions 0.1.0 and 0.1.1 allowed Android's backup, so their settings, including the Apple Music user token, could be included in your phone's backup. 0.1.2 and later don't. Signing out in Foldman deletes the token from the phone, but not from a backup that was already made. Android replaces backups over time, and you can delete an existing one in your phone's Google backup settings.

## What goes over the network

- **Apple** (`api.music.apple.com` and Apple's streaming services), only when you use Apple Music through MusicKit:
  - Foldman fetches your library playlists and their songs with your user token.
  - Apple's MusicKit player streams the songs and, like any Apple Music player, reports what you played back to Apple.
  - Apple's handling of that data is covered by [Apple's privacy policy](https://www.apple.com/legal/privacy/).
- **Web radio**, only when you use it:
  - a search sends the words you typed to the [Radio Browser](https://www.radio-browser.info) directory (`*.api.radio-browser.info`), which answers with matching stations;
  - the station you play streams from that station's own server, which sees your IP address like any radio player; station logos are downloaded from wherever the directory points.
- **Album art URLs** that YT Music, Apple Music or Apple's API hand to Foldman are downloaded to draw the cassette label.

Local music never leaves the phone.

## Signing out

In Foldman, go to Apple Music → **Sign out** to forget your Apple Music user token. Turning off Foldman's notification access in Android settings stops it seeing other apps' playback.
