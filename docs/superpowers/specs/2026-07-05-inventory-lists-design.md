# Inventory Lists (spoken product + quantity + price) — Design

**Date:** 2026-07-05
**Status:** Approved (design), pending implementation plan
**Scope:** Android, **local, single active list**, v1.

## Goal

A new "Inventory" mode on the phone where the operator speaks one line at a time —
**product, quantity, price** in that fixed order — and the app parses it into a table row,
computes **line value = quantity × price** instantly, and keeps a running **grand total**.
The finished list exports to **PDF**. This is for stock-taking / inventory valuation, distinct
from the existing order-list flow (which is product-only → PDF grouped by distributor, no prices).

## Why

- Counting stock and valuing it (quantity × price, summed) is a routine warehouse task.
- Speaking the whole line is faster than typing three fields per item on a phone.
- Prices are spoken (not pulled from the catalog) so the operator can value anything at any
  price, including products not in the catalog.

## Decisions (locked during brainstorming)

- **Price: always spoken** (each line carries its own price).
- **Product: hybrid** — try to match the spoken name against the catalog (existing fuzzy
  resolver); if no good match, keep the spoken text as a free-text name.
- **Format: fixed order** product → quantity → price.
- **Export: PDF** (reuse the existing client-side PDF generator).
- **Storage: local, single active list** ("New list" clears it). No sync, no multi-list.
- **Trigger: tap-to-speak per line** (not continuous listening) — one line, visible row,
  correct if needed, tap again. Safer for 3-field parsing.
- **Money: stored in minor units (bani, integer)** — not floating point.
- **Missing field → row added with the blank field highlighted** for manual entry; nothing lost.

## Non-goals (v1)

- Team sync / real-time multi-phone inventory (would need backend entities + WebSocket).
- Multiple saved inventory lists.
- Excel/CSV export.
- Units of measure on quantity (quantity is a plain number in v1).
- Continuous listening for inventory.

---

## Architecture (Android, local)

A new **Inventory** feature, reachable from Home + the drawer, built from focused units:

### 1. `InventoryLineParser` (pure Kotlin, no Android deps) — the core

Input: the spoken text + a product-resolution callback. Output: a `ParsedInventoryLine`.

Strategy:
1. **Resolve product first.** Run the spoken text through the existing product resolver
   (`ResolveSpokenProductUseCase` / `ProductRanker`, in `data/usecase` + `util`). If it returns a
   confident match, take that product and **consume the matched name span** from the text — this
   is what lets `"Cola 2L"` work without mistaking `2L` for the quantity. If no confident match,
   the product name = the leading non-numeric words (free-text), `productId = null`.
2. **Extract the two numbers** from the remaining text: **first number = quantity**,
   **second number = price**. Handle Romanian formats:
   - integers and decimals: `5`, `4.5`, `4,50`
   - lei/bani pattern: `"4 lei 50"` / `"4 lei 50 bani"` → 4.50; `"lei"`/`"ron"` currency words
   - decimal separators: `,` and `.`, and the spoken word `"virgulă"`
   Price is normalized to **bani (integer)**; quantity is a number (Double, to allow fractional
   amounts like 2.5).
3. **Partial results.** If quantity or price is missing/unparseable, return the line with that
   field `null` and a flag so the UI highlights it for manual entry. The product name alone is
   enough to create a row.

`ParsedInventoryLine`: `{ name: String, productId: Long?, quantity: Double?, priceBani: Long?,
missingFields: Set<Field> }`. The parser is the unit under heaviest test.

### 2. Data model (Room, `AppDatabase`)

A single table for the one active list — no list entity needed in v1:

`InventoryItemEntity`:
- `id: Long` (PK, autogen)
- `name: String` — resolved or free-text product name
- `productId: Long?` — catalog product id when matched, else null
- `quantity: Double`
- `priceBani: Long` — unit price in bani (minor units)

Line value and grand total are **computed** (`quantity * priceBani`), not stored. "New list" =
`DELETE FROM inventory_items`. A Room migration adds the table (DB version bump; the app uses
destructive migration today, so this is additive and safe).

### 3. `InventoryViewModel`

- Exposes the item list as a `Flow` from Room, plus a derived **grand total** (sum of line values,
  in bani).
- `addSpokenLine(text)` → parser → insert `InventoryItemEntity` (with nulls filled as 0/blank +
  the missing-field flags surfaced to the UI for that new row).
- `updateRow(id, quantity?, priceBani?, name?)`, `deleteRow(id)`, `clearList()`.
- `exportPdf()` → delegates to the PDF unit.

### 4. `InventoryScreen` (Compose)

- **Mic button** (tap-to-speak one line; uses the existing `AndroidSpeechProvider` /
  `SpeechRepository` + `SpeechState`).
- **Table**: columns **Produs | Cant. | Preț | Valoare**. Rows are **editable in place** (tap a
  cell to fix name/quantity/price) and **deletable**. A newly-added row with a missing field shows
  that cell highlighted.
- **Grand total** row at the bottom, recomputed live.
- **Export PDF** and **New list** (clear, with confirm) buttons.

### 5. PDF export

Reuse `PdfRepository`'s A4 layout / pagination / footer machinery (as `GeneratePdfsUseCase` does
for orders) with an **inventory layout**: title + date, table (Produs, Cant., Preț, Valoare), and
a **Grand total** row. Delivered via the same share intent as the order PDFs. Prices formatted from
bani (`/100`, 2 decimals, "lei").

### 6. Navigation

Add an "Inventar" entry point on `HomeScreen` and in the drawer, and an `inventory` route in
`NavGraph`.

## Data flow

```
tap mic → AndroidSpeechProvider → final text
  → InventoryViewModel.addSpokenLine(text)
    → InventoryLineParser.parse(text, resolveProduct)
      → resolve product (consume name span) → extract quantity, price(bani)
    → insert InventoryItemEntity (missing fields flagged)
  → Room Flow emits → InventoryScreen re-renders table + recomputes grand total
tap Export PDF → PdfRepository (inventory layout) → share intent
```

## Error handling

| Situation | Behavior |
|---|---|
| Product matches catalog | Use catalog name + `productId`; consume its span before number extraction |
| Product not in catalog | Free-text name, `productId = null` |
| Product name contains a number (`Cola 2L`) | Catalog match consumes it; only trailing numbers parsed as qty/price |
| Quantity missing/unparseable | Row created, quantity cell blank + highlighted for manual fill |
| Price missing/unparseable | Row created, price cell blank + highlighted for manual fill |
| Total misparse | Fully editable table — user fixes any cell |
| Price like `"4 lei 50"` / `"4,50"` / `"4.5"` | Normalized to bani |

## Testing

- **`InventoryLineParser`** — extensive JVM unit tests: fixed-order parsing; product-with-number;
  missing quantity; missing price; lei/bani/ron patterns; comma vs dot decimals; multi-word product
  names; free-text fallback; quantity as decimal. This is the risk center and gets the most tests.
- **Grand-total / line-value math** — ViewModel-level unit test (bani arithmetic, no float drift).
- **Room** — DAO insert/query/delete + clear, in-memory database test (matches existing DAO tests).
- **UI / on-device** — deferred to manual verification (consistent with the A1/B2 approach; the
  emulator/instrumented path is unreliable in this environment). Gate on **compile + JVM unit tests**.

## Reuse

- Product resolution: `ResolveSpokenProductUseCase` (`data/usecase`) + `ProductRanker` (`util`).
- Speech: `AndroidSpeechProvider` + `SpeechState` (`data/speech`) + `SpeechRepository`.
- PDF: `PdfRepository` (`data/repository`) machinery.
- DB/navigation patterns: `AppDatabase`, `NavGraph`, `HomeScreen`.

## Rollout

Additive Room table + new screen/route; no backend changes; no effect on the order-list flow.
Ships as its own PR off `development`.
