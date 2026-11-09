# mlib - Music Library + minimal player

Tiny dependency-free Android app (plain Java, no AndroidX) that lists your music by Albums / Artists / Songs and plays it with a deliberately minimal player: a queue with previous / stop / next, nothing else (no shuffle, repeat, pause or sleep timer).

## Build with GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the `.github/workflows/build.yml` path).
2. Open the **Actions** tab -> "Build APK" runs on every push (or press **Run workflow**).
3. The workflow installs Gradle 8.11.1 and the Android 16 (API 36) SDK itself. When it finishes, open the run and download the **mlib-apk** artifact, unzip, install the APK.

## Use
- First launch asks you to pick your music folder (system picker, no storage permission needed).
- It scans all sub-folders recursively. Names show up first, tags and covers fill in after.
- Top-right: the refresh arrow re-reads only new or changed files (it shows progress while scanning). The folder icon changes your music folder.
- Tap the stats line at the top for full stats. Tap an artist -> albums, album -> songs, song -> details.
- Back button (or swipe back) closes the queue first, then returns to the previous list at the exact scroll position you left it.
- Package name `com.mlib.io`, launcher name `mlib`, targets Android 16 (API 36), min Android 8.0 (API 26).

## Player
- **Single song:** tap a song. It plays, then playback stops.
- **Album / artist:** long-press an album or artist -> *Play*, *Play next* or *Add to queue*. Long-press a song for the same options plus *Details*.
- **Play all:** the *Play all* button above the list plays everything currently shown (current tab, artist/album filter, search, and sort for the Songs tab).
- **Mini player:** appears above the search bar while something plays: previous, stop, next and a thin progress line. Tap it to open the queue.
- **Queue:** tap a song to jump to it, drag the grip on the right to reorder, tap the cross to remove. The current song is highlighted. Back closes the queue.
- **Stop** clears the queue and ends playback. When the queue ends playback stops by itself. Previous restarts the song if it has played for more than 3 seconds.
- Keeps playing with the screen off (foreground media service) and can be controlled from the notification, lock screen and headset buttons. It stops when headphones are unplugged and pauses briefly for calls or other audio.
- Android 13+ asks once to allow notifications; playback works without it, you just won't see the notification.
- Unplayable or missing files are skipped.
