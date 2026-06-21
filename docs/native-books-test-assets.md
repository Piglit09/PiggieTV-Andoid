# Native PTV Books Test Assets

Date: June 13, 2026

Use public-domain, permissively licensed, or self-created files only. Do not commit copyrighted books, comics, manga, or commercial samples to this repository.

Recommended local staging directory:

```text
test-assets\books
```

Generate the local dummy asset set with:

```powershell
.\tools\books\generate-books-test-assets.ps1
```

The script creates the folder structure and generated files without network downloads or paid tools. It overwrites only the known generated sample filenames.

## Required Sample Files

| Asset | Recommended Filename | Notes |
| --- | --- | --- |
| Small EPUB | `epub\small-nav.epub` | Generated EPUB 3 sample with nav document. |
| Large EPUB | `epub\large-generated.epub` | Generated text-heavy EPUB with many chapters. |
| EPUB with nav | `epub\small-nav.epub` | EPUB 3 nav document should expose TOC titles. |
| EPUB with NCX only | `epub\ncx-only.epub` | EPUB 2-style NCX TOC fallback sample. |
| Broken EPUB | `epub\broken-metadata.epub` | Intentionally missing metadata; the archive remains valid so the reader can test graceful fallback behavior. |
| Short PDF | `pdf\short-under-50-pages.pdf` | Generated 12-page PDF. |
| 300+ page PDF | `pdf\long-320-pages.pdf` | Generated 320-page PDF. |
| Large scanned PDF | `pdf\simulated-scanned.pdf` | Generated vector-heavy scanned-page simulation; not a true image-scan PDF. |
| Small CBZ | `cbz\small-generated.cbz` | Generated PNG pages zipped as CBZ. |
| Large CBZ | `cbz\large-generated.cbz` | Generated 80-page CBZ. |
| RTL manga CBZ | `cbz\rtl-manga-generated.cbz` | Generated right-to-left direction marker sample. |
| TXT | `txt\short.txt`, `txt\long.txt` | Generated short and long text. |
| Unsupported CBR | `unsupported\unsupported.cbr` | Generated placeholder, not a real RAR archive. |
| Unsupported MOBI | `unsupported\unsupported.mobi` | Generated placeholder for detection. |
| Unsupported AZW | `unsupported\unsupported.azw` | Generated placeholder for detection. |
| Unsupported AZW3 | `unsupported\unsupported.azw3` | Generated placeholder for detection. |
| Markdown | `unsupported\markdown.md` | Generated Markdown. |
| HTML | `unsupported\html.html` | Generated HTML. |
| Unknown | `unsupported\unknown.booktest` | Generated unknown extension sample. |

## Suggested Sources

- Project Gutenberg public-domain EPUB/TXT files.
- Self-created PDFs from local documents.
- Self-created CBZ files made from generated PNG/JPEG test pages.
- Locally generated long text and image-heavy PDFs for stress testing.

## Validation Notes

- Keep a note of each file's approximate size and page count.
- Do not rely on one perfect sample. The goal is to exercise parser, renderer, memory, resume, and unsupported-format paths.
- If a file cannot be sourced safely, mark its runtime validation row `BLOCKED` rather than substituting copyrighted material.
- The generated simulated scanned PDF uses dense vector bands rather than embedded photographed page images. It is useful for page-count and render stress, but not a perfect substitute for a true large scanned PDF.
