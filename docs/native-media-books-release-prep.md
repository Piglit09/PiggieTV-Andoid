# Native Media and Books Release Prep

Date: June 13, 2026

This document summarizes the current PiggieTV Android native Music, Android Auto, and Books work. It is a release-prep handoff, not a release approval. The biggest remaining blocker is runtime validation on real Android hardware and Android Auto / DHU.

## 1. Native Music

Native Music has moved away from the old Jellyfin player path and now uses a native PiggieTV experience.

Landed:

- Native Music tab and Music home sections.
- Custom PTV music player and mini-player.
- Queue state, active track metadata, and queue continuation behavior.
- Shuffle and repeat controls, including repeat-one/repeat-all behavior.
- Seek, elapsed time, remaining time, progress display, and buffering state.
- Favorite/like action and add-to-playlist workflows.
- Codec/path status messaging for supported, limited, unknown, and unsupported formats.
- Recommendation logic using available Jellyfin music data such as artist, album, genre, favorites, recent play, play count where available, and recently added music.
- Large-library hardening work including lazy/paged loading patterns, search debounce, placeholders, retry states, and avoiding unnecessary giant in-memory loads.

Still needs runtime validation:

- Long-session playback on a real phone.
- Background/foreground behavior.
- Notification and lockscreen controls.
- Bluetooth headset controls.
- Network interruption and failed-track continuation.
- MP3, FLAC, OPUS, AAC/M4A, OGG/Vorbis, WAV, ALAC, and WMA/limited-format behavior on real devices.
- Playlist duplicate handling and Jellyfin API failure handling against a real server.
- Mini-player metadata correctness after rapid track switching.

## 2. Android Auto

Android Auto support is native and Media3-based. The old Jellyfin sessionbrowser path should not be reintroduced as the active music service.

Landed:

- Native Media3 service for PTV Music.
- `MediaLibraryService` integration.
- Android Auto browse categories for music library entry points.
- Search routing.
- Playback commands routed into the native `MusicPlaybackController`.
- Shared ExoPlayer/session path through the native service/player bridge.
- Queue persistence for process-death resume.
- Defensive browse/search behavior for large libraries and Jellyfin failures.
- Developer validation checklist and local helper scripts.

Validation tooling:

- `tools\android-auto\check-android-auto-env.ps1`
- `tools\android-auto\install-debug-apk.ps1`
- `tools\android-auto\capture-android-auto-logs.ps1`
- `tools\android-auto\run-validation-preflight.ps1`
- `docs\android-auto-ptv-music-validation.md`
- `docs\android-auto-validation-results.md`

Blocked status:

- Android Auto runtime validation is currently blocked because no authorized Android device/emulator is connected.
- DHU was not available during previous validation setup.
- Real-car/head-unit behavior has not been verified.

Next commands:

```powershell
.\tools\android-auto\run-validation-preflight.ps1 -Install
.\tools\android-auto\capture-android-auto-logs.ps1 -Clear
```

## 3. Native Books

Books remains fully native. No WebView is used for the final Books experience, and the default Jellyfin reader is not the target fallback.

Landed:

- Native Books home.
- Jellyfin Books/comics library data is the primary Books source: item IDs, cover images, and download/media URLs flow into the native PTV reader.
- Native book detail page.
- Native reader for EPUB, PDF, CBZ, and TXT.
- Clean PTV unsupported-format messaging for CBR, MOBI, AZW/AZW3, Markdown, HTML, and unknown formats.
- Structured EPUB parser that reads EPUB container, OPF package, manifest, spine, EPUB3 nav, and NCX fallback.
- Native EPUB text rendering with headings, paragraph spacing, basic bold/italic markers, image placeholders, note markers, and pre/code-like line preservation.
- PDF/CBZ native rendering with device-memory-aware bitmap cache, bounded prefetch, page retry, cleanup on close/rotation, and stale-render guards.
- Reader settings for theme, font size, line spacing, image fit, single-page mode, RTL manga mode, and zoom.
- Local progress/resume by page and chapter approximation, with conservative Jellyfin user-data progress sync when a Jellyfin item ID is available.
- Reader TOC drawer, page slider, resume banner, tap zones, status/debug panel, and Start Over confirmation.
- Books validation scripts.
- Generated non-copyrighted local test assets.

Validation tooling:

- `tools\books\check-books-env.ps1`
- `tools\books\install-debug-apk.ps1`
- `tools\books\capture-books-logs.ps1`
- `tools\books\run-books-validation-preflight.ps1`
- `tools\books\generate-books-test-assets.ps1`
- `docs\native-books-runtime-validation.md`
- `docs\native-books-validation-results.md`
- `docs\native-books-test-assets.md`

Blocked status:

- Books runtime validation is currently blocked because no authorized Android device/emulator is connected.
- Large-file behavior has compile/unit coverage and generated samples, but still needs real hardware validation.

Next commands:

```powershell
.\tools\books\run-books-validation-preflight.ps1 -Install
.\tools\books\capture-books-logs.ps1 -Clear
```

## 4. Test Assets

Generated non-copyrighted Books validation assets live under:

```text
test-assets\books\
```

Current generated sample counts:

- EPUB: 4
- PDF: 3
- CBZ: 3
- TXT: 2
- Unsupported: 7

Regenerate assets with:

```powershell
.\tools\books\generate-books-test-assets.ps1
```

Notes:

- Assets are generated dummy content only.
- The simulated scanned PDF uses generated vector bands, not photographed page scans.
- Unsupported `.cbr`, `.mobi`, `.azw`, and `.azw3` files are placeholder detection samples, not real ebook archives.

## 5. Release Risk Assessment

| Area | Risk | Severity | Status | Required Validation/Fix |
| --- | --- | --- | --- | --- |
| Android Auto | Android Auto has not been runtime-tested. | High | Blocked | Connect authorized phone/emulator, run DHU validation, capture logs. |
| Android Auto | DHU and real-car/head-unit behavior not verified. | High | Blocked | Test DHU first, then real car/head unit if available. |
| Android Auto | Browse/playback/search may behave differently on head units. | High | Needs validation | Run browse categories, search, play/pause, next/previous, seek, shuffle/repeat. |
| Books | Native Books reader has not been runtime-tested on device. | High | Blocked | Install on phone/tablet and run generated asset matrix. |
| Books | Large PDF/CBZ memory behavior not verified on real hardware. | High | Needs validation | Test long PDF, simulated scanned PDF, large CBZ, rotation, close-while-rendering. |
| Books | Android TV / Fire TV D-pad reader behavior not verified. | Medium | Needs validation | Install on TV/Fire TV device or emulator and test single-page D-pad controls. |
| Books | Server-side Books progress only syncs for Jellyfin-backed items; OPDS fallback remains local-only. | Medium | Known limitation | Validate Jellyfin item progress writes on device and keep OPDS fallback local-only. |
| Music | Native Music playback needs real-device long-session validation. | High | Needs validation | Test formats, backgrounding, notification controls, network interruption, queue errors. |
| Music | Playlist workflows depend on Jellyfin API behavior. | Medium | Needs validation | Test existing playlist, new playlist, duplicates, API failure. |
| Code Quality | Detekt still fails repo-wide with unrelated existing findings. | Medium | Known limitation | Do not block this work on existing findings; avoid adding new feature findings. |
| Unsupported formats | CBR/RAR, MOBI, AZW/AZW3 are intentionally blocked. | Low | Intended | Confirm clean PTV messaging and no crash. |

## 6. Pre-release Validation Checklist

- [ ] Build proprietary debug APK.
- [ ] Run unit tests.
- [ ] Run detekt and confirm no new feature-specific findings.
- [ ] Install on Android phone.
- [ ] Install on Android tablet if available.
- [ ] Install on Android TV / Fire TV if available.
- [ ] Run Android Auto DHU validation.
- [ ] Run real car/head-unit validation if available.
- [ ] Test Music playback with MP3.
- [ ] Test Music playback with FLAC.
- [ ] Test Music playback with OPUS.
- [ ] Test Music playback with AAC/M4A, OGG/Vorbis, WAV, ALAC, and WMA/limited formats where available.
- [ ] Test Music queue, mini-player, shuffle/repeat, seek, buffering, and failed-track continuation.
- [ ] Test Music favorite/add-to-playlist flows.
- [ ] Test Android Auto browse, playback, and search.
- [ ] Test Books EPUB samples.
- [ ] Test Books PDF samples.
- [ ] Test Books CBZ samples.
- [ ] Test Books TXT samples.
- [ ] Test unsupported Books files.
- [ ] Test rotation/background/resume for Music and Books.
- [ ] Test app restart/process-death restore paths.
- [ ] Watch memory behavior during large PDF/CBZ rendering.
- [ ] Capture focused logs for Android Auto and Books validation.

## 7. Known Limitations

- EPUB rendering is native text extraction and cleanup only; full EPUB CSS/layout rendering is not implemented yet.
- CBR/RAR comics are unsupported.
- MOBI and AZW/AZW3 are unsupported.
- Books progress is local-first. Jellyfin-backed items attempt conservative user-data progress sync; OPDS fallback remains local-only.
- Android Auto runtime behavior is unvalidated.
- Large-file Books runtime behavior is unvalidated on real hardware.
- Android TV / Fire TV D-pad reader behavior is unvalidated on real hardware.
- Repo-wide detekt still has existing unrelated findings.

## 8. Recommended Next Phase

1. Connect an authorized Android phone.
2. Run Android Auto validation.
3. Run Books validation with generated assets.
4. Fix only validation bugs found during runtime testing.
5. Then consider a release candidate.

Recommended next command after connecting hardware:

```powershell
.\tools\android-auto\run-validation-preflight.ps1 -Install
```
