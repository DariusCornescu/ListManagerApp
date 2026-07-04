# PDF Template Redesign — Design Spec

**Date:** 2026-06-10
**Status:** Approved (style and content confirmed by Darius)
**Scope:** Android only — `android-native/.../data/repository/PdfRepository.kt`

## Goal

Replace the current "spreadsheet printout" PDF (full cell borders, gray header
fill) with a clean professional order document, and add a paper check-off
column for the person picking the order.

## Decisions made

- **Style:** Clean professional — no heavy cell borders, subtle alternating row
  shading, clear typographic hierarchy. Black/gray only (print/photocopy safe).
- **Columns:** Qty, Product, Size/Type (unchanged data) **plus** a new empty
  check-off box column on the right. No barcode column, no notes/contact block.
- **Rendering approach:** keep the hand-drawn `android.graphics.pdf.PdfDocument`
  canvas. No HTML/WebView, no third-party PDF library, no new dependencies.

## Layout (A4, 595×842 pt)

### Header — first page only
- "ORDER LIST" large bold (~24pt), session date right-aligned on the same baseline.
- Distributor name prominent (~16pt) below.
- One thin horizontal rule under the header block. No boxes/rects.

### Table
- Column header row: small-caps style labels (QTY / PRODUCT / SIZE/TYPE / ✓),
  thin rule underneath. Repeated at the top of every continuation page.
- Data rows: **no borders**. Alternating row background (every 2nd row) in very
  light gray (~#F5F5F5). Vertical rhythm from row height + padding.
- Qty right-aligned in its column; check-off column renders a ~12pt empty
  square outline per row.
- **Long product names wrap to up to 2 lines** (replaces `...` truncation).
  Row height grows for wrapped rows; items-per-page becomes dynamic, computed
  from accumulated row heights rather than a fixed count.

### Footer / totals
- Last page: thin rule, then "TOTAL ITEMS: n" bold, right-aligned. No filled box.
- Every page: "ListManager · page X of Y" small gray, bottom of page.

## Unchanged

- `upsertDistributorPdf(distributorName, sessionDate, items)` public signature
  and `PdfItem` model.
- File naming (`Order_<distributor>_<timestamp>.pdf`) and save location
  (`Documents/ListManager_PDFs`).
- Grouping by distributor in `GeneratePdfsUseCase`.

## Error handling

Same as today (IO on `Dispatchers.IO`, caller handles failures). Wrapping logic
must handle: empty item list, very long single words (hard-break), and the
degenerate case where one wrapped row exceeds remaining page space (push to
next page).

## Testing

- Extract pagination/wrapping math into a small pure function/class (no
  `Canvas` dependency) and unit-test it in `testDebug`: items-per-page with
  mixed 1- and 2-line rows, page-count correctness, hard-break of long words.
- Manual verification: generate a PDF from a real session on-device with >1
  page of items and a few very long product names; check print preview in
  black & white.
