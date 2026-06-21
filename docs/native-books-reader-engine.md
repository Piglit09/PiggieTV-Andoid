# Native PTV Books Reader Engine Notes

Date: June 13, 2026

## EPUB Engine

The native reader now uses a structured EPUB parser instead of concatenating every HTML file in ZIP order.

- Reads `META-INF/container.xml`.
- Resolves the OPF package document.
- Parses OPF manifest entries.
- Uses OPF spine item order as the reading order.
- Reads EPUB 3 nav documents when available.
- Falls back to NCX table of contents when available.
- Falls back to document headings or generated chapter names when metadata is missing.
- Converts XHTML/HTML content into native text pages without WebView.
- Preserves headings, paragraph breaks, line breaks, and common HTML entities.
- Stores chapter metadata for TOC navigation and chapter-aware resume.

## PDF and CBZ Cache Policy

PDF and CBZ pages are still rendered natively, but rendered bitmap retention is now bounded by a device-memory-aware cache policy.

- Cache budget is based on the app heap cap.
- Budget is clamped between a safe low-memory floor and high-memory ceiling.
- Prefetch window shrinks on low-memory devices.
- Nearby pages are prefetched around the current page.
- Old image pages are recycled when the bitmap cache exceeds its budget.
- Render failures are converted into readable PTV page-level errors instead of crashing the reader.

## Navigation

Reader controls now include:

- EPUB table of contents panel.
- Current chapter label.
- Page slider.
- Jump to beginning/end.
- Resume banner.
- Center tap zone to hide/show controls in single-page mode.
- Left/right tap zones for single-page page turns.

## Progress Sync Finding

PiggieTV Android now prefers Jellyfin Books libraries as the primary Books source. Jellyfin-backed books carry stable item IDs, Jellyfin cover URLs, and Jellyfin download/media URLs into the custom native reader.

Local PTV progress remains the source of truth for reader resume. When a Jellyfin item ID is available, the reader also attempts a conservative server user-data progress update. OPDS/Calibre fallback books still remain local-only because OPDS ids cannot safely be mapped back to Jellyfin item IDs.
