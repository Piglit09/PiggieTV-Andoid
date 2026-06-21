# Android Auto PTV Music Validation

This checklist validates the native PTV Music Android Auto integration. The active service must remain the Media3-based `PtvMusicService`; do not switch back to the old Jellyfin `sessionbrowser` service for these tests.

## Local Helper Scripts

The repo includes repeatable Windows PowerShell helpers for Android Auto validation setup:

```powershell
.\tools\android-auto\check-android-auto-env.ps1
.\tools\android-auto\run-validation-preflight.ps1
.\tools\android-auto\install-debug-apk.ps1
.\tools\android-auto\capture-android-auto-logs.ps1 -Clear
```

Use `check-android-auto-env.ps1` first. It prints the repo root, Android SDK roots, `adb` location, connected devices, DHU candidates, and generated proprietary debug APK candidates.
When a device is attached, it also checks the installed debug package for Android Auto media service declarations:

```powershell
.\tools\android-auto\check-android-auto-env.ps1 -PackageName com.piggietv.android.debug
```

Use `run-validation-preflight.ps1` before a validation session. It runs the environment check, builds the proprietary debug APK, discovers the latest APK under `app\build\outputs\apk\proprietary\debug\*.apk`, checks devices, and prints the next manual DHU or real-car steps. Add `-Install` after a phone is connected and authorized:

```powershell
.\tools\android-auto\run-validation-preflight.ps1 -Install
```

If more than one authorized device is attached, pass a serial:

```powershell
.\tools\android-auto\run-validation-preflight.ps1 -Install -Serial <device-serial>
```

The scripts use `adb` from `PATH` when available. If `adb` is not on `PATH`, they fall back to common SDK paths such as:

```text
C:\Users\Piggie\AppData\Local\Android\Sdk\platform-tools\adb.exe
```

## Build And Install

1. From the repository root, build the proprietary debug variant:

   ```powershell
   .\gradlew.bat :app:assembleProprietaryDebug
   ```

2. Confirm a device or emulator is connected and authorized:

   ```powershell
   .\tools\android-auto\check-android-auto-env.ps1
   ```

   Or call the known local `adb.exe` directly:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices -l
   ```

3. Install the latest generated proprietary debug APK:

   ```powershell
   .\tools\android-auto\install-debug-apk.ps1
   ```

   Do not assume the APK is named `app-proprietary-debug.apk`. The helper discovers the newest `*.apk` under:

   ```text
   app\build\outputs\apk\proprietary\debug
   ```

4. Open PTV on the phone once and sign in to the Jellyfin server. Android Auto needs the saved server/user session before it can browse music.

5. On Android 13 or newer, grant notification permission when prompted. If it was previously denied, enable notifications from Android system settings for the PTV app.

## Connect And Authorize A Phone

1. Enable Android developer options on the phone.
2. Enable `USB debugging`.
3. Connect the phone by USB.
4. Accept the RSA authorization prompt on the phone.
5. Verify the device state is `device`, not `unauthorized` or `offline`:

   ```powershell
   .\tools\android-auto\check-android-auto-env.ps1
   ```

6. If the prompt does not appear, unplug/replug the phone, toggle USB debugging, or run:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" kill-server
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" start-server
   ```

## Enable Android Auto Developer Mode

1. Install or update Android Auto on the phone.
2. Open Android Auto on the phone.
3. Open Android Auto settings.
4. Scroll to `Version` and tap it repeatedly until developer mode is enabled.
5. Open the overflow menu and choose `Developer settings`.
6. Enable `Unknown sources` if testing a debug or sideloaded build.
7. Enable `Start head unit server`.

Debug builds use the package id `com.piggietv.android.debug`. If PTV does not appear in Android Auto after install, first confirm Android Auto developer mode and `Unknown sources` are enabled, then verify the installed package advertises the native Media3 service:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" shell dumpsys package com.piggietv.android.debug | findstr /i "PtvMusicService MediaLibraryService MediaBrowserService car.application automotive mediaPlayback"
```

Expected findings include `org.jellyfin.mobile.feature.music.auto.PtvMusicService`, `androidx.media3.session.MediaLibraryService`, `android.media.browse.MediaBrowserService`, `com.google.android.gms.car.application`, and `mediaPlayback`.

## Desktop Head Unit

1. Install the Android Auto Desktop Head Unit from Android Studio:
   - Open `Settings`.
   - Go to `Appearance & Behavior` > `System Settings` > `Android SDK`.
   - Open the `SDK Tools` tab.
   - Enable `Show Package Details` if needed.
   - Install `Android Auto Desktop Head Unit Emulator`.
2. Start the head unit server from Android Auto developer settings on the phone.
3. Forward the DHU socket:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" forward tcp:5277 tcp:5277
   ```

4. Launch DHU from the Android SDK location. Common paths:

   ```powershell
   & "$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\desktop-head-unit.exe"
   ```

   or:

   ```powershell
   & "$env:ANDROID_HOME\extras\google\auto\desktop-head-unit.exe"
   ```

5. If DHU is unavailable on the workstation, skip to the real-car checklist below and record that DHU was not available.

## Focused Log Capture

Start focused logs before opening Android Auto or DHU:

```powershell
.\tools\android-auto\capture-android-auto-logs.ps1 -Clear
```

The script writes timestamped filtered logs under:

```text
logs\android-auto
```

It filters for:

- `PtvMusic`
- `AndroidAuto`
- `MediaLibraryService`
- `MediaSession`
- `ExoPlayer`
- `MusicPlayback`
- `MusicRepository`

For a bounded capture, use:

```powershell
.\tools\android-auto\capture-android-auto-logs.ps1 -Clear -DurationSeconds 120
```

## DHU Media App Checks

1. Confirm PTV appears in the Android Auto media app launcher.
2. Open PTV in Android Auto.
3. Confirm the root browse screen shows:
   - Recently Added
   - Albums
   - Artists
   - Songs
   - Genres
   - Playlists
   - Liked Songs
   - Recently Played
   - Recommended For You
4. Browse each category and confirm the list returns quickly.
5. Confirm folder-like items are browsable:
   - Albums open to songs.
   - Artists open to albums.
   - Genres open to songs.
   - Playlists open to songs.
6. Confirm playable songs show as playable media rows.
7. Start playback from:
   - Songs
   - An album child song
   - A playlist child song
   - Liked Songs
8. Confirm playback starts in the native PTV player and the phone mini-player matches the Android Auto metadata.
9. Test play and pause from DHU.
10. Test next and previous from DHU.
11. Test seek from DHU if the head unit exposes seeking.
12. Test shuffle on and off.
13. Test repeat off, repeat all, and repeat one.
14. Search for:
   - A track title
   - An artist name
   - An album name
15. Start a search result and confirm playback uses the native music queue.
16. Disconnect DHU and reconnect. Confirm PTV still appears and can browse.
17. While connected, move the phone app foreground/background:
   - PTV foreground with expanded player open.
   - PTV background.
   - Phone screen locked.
18. Confirm Android Auto, lockscreen, media notification, and in-app player stay in sync for:
   - Track title
   - Artist
   - Album art when available
   - Play/pause state
   - Elapsed time
19. Force-stop PTV, reconnect Android Auto, and confirm:
   - PTV appears as a media app.
   - The saved queue metadata restores if a queue was previously saved.
   - Playback does not start automatically.
   - Pressing play resumes from the saved queue and approximate position.
20. Delete or hide a saved track from Jellyfin, then reconnect. Confirm PTV skips missing items and does not crash.

## Real-Car Checklist

Run these checks on at least one wired and one wireless Android Auto environment when possible.

1. Install the same proprietary debug build.
2. Enable Android Auto developer `Unknown sources` for sideloaded debug builds.
3. Connect to the car head unit.
4. Confirm PTV appears in the media app launcher.
5. Open PTV and browse every root category.
6. Start playback from Songs, Albums, Playlists, and Search.
7. Verify steering wheel controls:
   - Play/pause
   - Next
   - Previous
8. Verify head unit controls:
   - Play/pause
   - Next
   - Previous
   - Seek if exposed
   - Shuffle
   - Repeat
9. Disconnect the phone mid-track and reconnect.
10. Turn the car off, wait for the head unit to fully sleep, then reconnect.
11. Confirm playback does not auto-start after reconnect unless the head unit explicitly sends play.
12. Confirm queue restore works after force-stopping the phone app.
13. Confirm phone lockscreen/media notification controls stay synchronized with the car UI.
14. Record the head unit make/model, Android phone model, Android version, and whether the connection was wired or wireless.

## Failure Notes To Capture

For any failure, capture:

- Android phone model and Android version.
- Android Auto version.
- DHU version or car/head-unit model.
- Wired or wireless connection.
- PTV build variant and version.
- Jellyfin server version.
- Category or track used.
- Whether browse, search, metadata, notification, or playback failed.
- Relevant `adb logcat` lines containing `PTV Music Auto`.
