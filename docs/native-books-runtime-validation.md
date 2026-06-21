# Native PTV Books Runtime Validation

Date: June 13, 2026

Use this checklist to validate the native PiggieTV Books reader on phones, tablets, landscape devices, and Android TV / Fire TV style screens. Books must remain native: no WebView and no default Jellyfin reader fallback.

## Setup

1. Run the Books environment check:

   ```powershell
   .\tools\books\check-books-env.ps1
   ```

2. Generate the local non-copyrighted sample assets if `test-assets\books` is missing or incomplete:

   ```powershell
   .\tools\books\generate-books-test-assets.ps1
   ```

3. Run preflight. This builds the proprietary debug APK, checks for sample assets, and checks for devices:

   ```powershell
   .\tools\books\run-books-validation-preflight.ps1
   ```

4. To install during preflight after an authorized device is connected:

   ```powershell
   .\tools\books\run-books-validation-preflight.ps1 -Install
   .\tools\books\run-books-validation-preflight.ps1 -Install -Serial <device-serial>
   ```

5. To install only the latest debug APK:

   ```powershell
   .\tools\books\install-debug-apk.ps1
   .\tools\books\install-debug-apk.ps1 -Serial <device-serial>
   .\tools\books\install-debug-apk.ps1 -ApkPath app\build\outputs\apk\proprietary\debug\<apk-name>.apk
   ```

6. Open PiggieTV and sign in to the Jellyfin server that contains a Books/comics library.
7. Confirm the Books home is labeled as a native Jellyfin Books source, then confirm the book detail page and custom native reader open normally.
8. If no Jellyfin Books library is available, configure the optional OPDS/Calibre fallback in settings and record the source as fallback-only.
9. Record device model, Android version, available RAM class if known, app build variant, and date tested.

The helper scripts discover the newest APK matching:

```text
app\build\outputs\apk\proprietary\debug\*.apk
```

Do not assume the APK is named `app-proprietary-debug.apk`.

## Log Capture

Start focused Books logs before opening or stressing the reader:

```powershell
.\tools\books\capture-books-logs.ps1 -Clear
```

For a fixed capture window:

```powershell
.\tools\books\capture-books-logs.ps1 -Clear -DurationSeconds 300
```

For multiple connected devices:

```powershell
.\tools\books\capture-books-logs.ps1 -Clear -Serial <device-serial>
```

Logs are written to:

```text
logs\books\
```

The focused filter includes `PTV Books`, `LibraryReader`, `LibraryEpubReader`, `LibraryBitmapCache`, `LibraryRepository`, `OpdsClient`, `PdfRenderer`, `Bitmap`, `OutOfMemory`, `CBZ`, and `EPUB`.

Jellyfin Books should be treated as the primary validation source. OPDS/Calibre is optional fallback coverage only and should be clearly labeled as such in results.

## Results

Save runtime results in:

```text
docs\native-books-validation-results.md
```

Use:

- `PASS` when the behavior works as expected.
- `FAIL` when the app or reader misbehaves, crashes, freezes, leaks stale state, or shows incorrect UI.
- `BLOCKED` when the test cannot run because hardware, sample files, credentials, or setup are unavailable.

Sample file requirements are documented in:

```text
docs\native-books-test-assets.md
```

The generated local assets live under:

```text
test-assets\books\
```

## File Matrix

For each item, record Pass, Fail, or Blocked. Attach logs for failures.

| Case | Expected Result | Result |
| --- | --- | --- |
| Small EPUB | Opens quickly, TOC if present, resume saves locally. |  |
| Large EPUB | Parses off the UI thread, chapters/pages appear without freezing. |  |
| EPUB with nav document | TOC titles match nav order and chapter jump works. |  |
| EPUB with NCX only | TOC falls back to NCX titles. |  |
| Broken EPUB metadata | Opens with generated/fallback titles or shows clean PTV error. |  |
| PDF under 50 pages | Pages render, zoom works, cache stays bounded. |  |
| PDF over 300 pages | Nearby pages prefetch, distant pages evict, no UI freeze. |  |
| Large scanned PDF | Memory remains stable; corrupt pages show page-level error. |  |
| Small CBZ | Pages render in order, fit-width/fit-height works. |  |
| Large CBZ | Cache evicts old bitmaps, page turns remain responsive. |  |
| Manga RTL CBZ | RTL toggle reverses reading order in single-page mode. |  |
| TXT | Font size, line spacing, theme, and resume work. |  |
| Unsupported CBR | Shows PTV unsupported message recommending CBZ. |  |
| Unsupported MOBI/AZW/AZW3 | Shows PTV unsupported message recommending EPUB/PDF. |  |
| Unsupported Markdown/HTML | Shows native-reader limitation and conversion guidance. |  |

## Lifecycle Matrix

| Case | Expected Result | Result |
| --- | --- | --- |
| App background/foreground | Current page and controls remain accurate; rendering resumes if needed. |  |
| Rotation | Reader restores same book/page without stale pages from prior render jobs. |  |
| Tablet landscape | Reader uses available width without clipped controls. |  |
| Phone portrait | Controls fit and text remains readable. |  |
| Android TV / Fire TV | D-pad left/right turns pages in single-page mode. |  |
| Low-memory device behavior | Cache window shrinks, old bitmaps recycle, no crash. |  |
| Resume after app restart | Local progress restores page/chapter and shows resume banner. |  |
| Switch books quickly | New book never shows stale pages or metadata from previous book. |  |
| Close reader during rendering | Render jobs cancel and bitmaps are recycled. |  |

## Reader Status Panel

During validation, open reader controls and use `Status` to inspect:

- Format.
- Current chapter/page.
- Total chapters/pages.
- Bitmap cache budget.
- Cached page count.
- Last render duration.
- Last reader error.
- Resume source.
- Memory warning state.

The panel is a tester/debug aid. It should not be treated as customer-facing reader content.

## Failure Notes

For any failure, capture:

- Exact file type and approximate size/page count.
- Whether it failed during open, render, navigation, rotation, backgrounding, or resume.
- Screenshot or short description of visible reader state.
- Relevant `adb logcat` lines.
- Whether Retry on a failed page succeeds.
