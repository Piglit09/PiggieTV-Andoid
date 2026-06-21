# Native PTV Books Phase Audit

Date: June 13, 2026

## Current Architecture

Books are native Compose screens under `app/src/main/java/org/jellyfin/mobile/feature/library`.

- `LibraryScreen.kt` renders the Books tab, local search, OPDS rows, book cards, and book detail.
- `LibraryViewModel.kt` loads OPDS data and enriches it with local PTV reader progress/favorites.
- `LibraryRepository.kt` prefers Jellyfin Books/comics library data and keeps OPDS/Calibre-style feeds as an optional fallback.
- `OpdsClient.kt` fetches/parses OPDS Atom feeds with basic/bearer auth.
- `LibraryReaderFragment.kt` opens a native reader fragment. It does not use a webview.

The Books tab is wired from `NativeHomeScreen.kt` through `NativeHomeFragment.kt` and `ActivityEvent.ReadLibraryBook`.

## What Works

- Native Books tab launches without the old Jellyfin web reader.
- OPDS home feeds can load books, covers, authors, series, categories, and acquisition links.
- Book detail shows cover, title, author, series/category metadata, format, file size when OPDS exposes link length, progress, favorite state, and read/download actions.
- Native reader can open:
  - EPUB as extracted text pages.
  - PDF via Android `PdfRenderer`.
  - CBZ as ZIP image pages.
  - TXT as chunked text pages.
- Local resume state is saved by reader key and restored when reopening a book.
- Local reader settings are persisted.
- Local favorite state exists as a PTV-side fallback when the OPDS server has no favorite API.
- Unsupported formats show PTV-branded messaging instead of crashing or opening a webview.

## What Is Still Limited

- The current primary source is Jellyfin Books data when a Books/comics library is available.
- OPDS/Calibre-style feeds remain optional fallback only and cannot safely sync Jellyfin server-side progress.
- EPUB chapter navigation is basic; the reader extracts XHTML/HTML text in spine-like filename order but does not parse the EPUB manifest/table of contents yet.
- PDF zoom is render-scale based and still limited compared with a full pan/zoom document engine.
- CBZ supports page order, fit mode, single-page mode, and RTL manga order, but it does not yet prefetch with a memory budget by device class.
- CBR/RAR is intentionally unsupported because Android has no built-in safe RAR reader.
- MOBI/AZW/AZW3 are unsupported and should be converted to EPUB/PDF.
- Download/offline uses the existing generic app download path; no Books-specific offline shelf has been built yet.

## Performance Notes

- OPDS network work runs off the UI thread.
- Large grids use Compose lazy containers.
- Reader downloads and parsing run on `Dispatchers.IO`.
- PDF and CBZ pages are rendered progressively around the visible page instead of loading every page at startup.
- Rendered image pages are recycled when the reader is disposed or settings force a rerender.

## Next Technical Risks

- Replace EPUB text extraction with a proper EPUB parser/TOC/spine model.
- Add Jellyfin-native book library support if the deployment uses Jellyfin Books instead of OPDS.
- Add device-memory-aware bitmap cache limits for comics and PDFs.
- Add real offline Books shelf behavior rather than relying on generic file downloads.
