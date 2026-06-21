# Native PTV Books Runtime Validation Results

Date tested:
Tester:
Build variant: proprietary debug
APK path:
Device model:
Android version:
Screen type: phone / tablet / TV / Fire TV / emulator
Available RAM class if known:
Books source: Jellyfin native / optional OPDS fallback / other
Log file:

## Environment

| Check | Result | Notes |
| --- | --- | --- |
| adb found |  |  |
| Authorized device attached |  |  |
| Latest proprietary debug APK found |  |  |
| APK installed |  |  |
| Books validation assets available |  |  |
| Log capture started |  |  |

## File Matrix

Use `PASS`, `FAIL`, or `BLOCKED`.

| Case | Result | Notes |
| --- | --- | --- |
| Small EPUB |  |  |
| Large EPUB |  |  |
| EPUB with nav document |  |  |
| EPUB with NCX only |  |  |
| Broken EPUB metadata |  |  |
| PDF under 50 pages |  |  |
| PDF over 300 pages |  |  |
| Large scanned PDF |  |  |
| Small CBZ |  |  |
| Large CBZ |  |  |
| Manga RTL CBZ |  |  |
| TXT |  |  |
| Unsupported CBR |  |  |
| Unsupported MOBI/AZW/AZW3 |  |  |
| Unsupported Markdown/HTML |  |  |

## Lifecycle Matrix

Use `PASS`, `FAIL`, or `BLOCKED`.

| Case | Result | Notes |
| --- | --- | --- |
| App background/foreground |  |  |
| Rotation |  |  |
| Tablet landscape |  |  |
| Phone portrait |  |  |
| Android TV / Fire TV D-pad |  |  |
| Low-memory device behavior |  |  |
| Resume after app restart |  |  |
| Switch books quickly |  |  |
| Close reader during rendering |  |  |

## Reader Status Panel Notes

Record notable values from the reader `Status` panel:

- Format:
- Current chapter/page:
- Total chapters/pages:
- Cache budget:
- Cached page count:
- Last render duration:
- Last reader error:
- Resume source:
- Memory warning state:

## Failures

For each failure, include:

- Exact file and approximate size/page count.
- Reproduction steps.
- Expected result.
- Actual result.
- Relevant log lines.
- Screenshot path if captured.
- Whether Retry succeeded.

## Summary

Overall result:
Blocking issues:
Non-blocking issues:
Follow-up fixes needed:
