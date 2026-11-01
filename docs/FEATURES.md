# FEFO, recovery, assessment PDF, alerts, mass balance, stock count, sale void, recall drill, invoice reading and lot scan

## Use

**Android → Sell / Lots → Scan a lot:** the scan button in the Sell search field opens Google's
code scanner (its own camera screen, so the app asks for no camera permission). A sellable lot opens
its sale sheet at once; a recalled or expired one opens the red sheet that says why it must not be
sold; a lot that is only on a recall list stays in the search as a blocked row; an unknown code shows
"no lot matches". A keyboard-wedge hand scanner types into the same field and its Enter does the
same. *Lots → Scan a lot* opens the scanned lot's trace. Codes read: the lot number itself (our QR
labels, Code 128) and GS1 pack labels, whose batch (AI 10) is our lot number on packs we produce
(AI 17 expiry, GTIN, weight and serial are parsed; an unknown AI stops parsing, nothing is guessed).
Devices without Google Play services get a message and keep the search field.

**Dashboard → lot card → Print lot label (QR):** a 60 × 40 mm label with a QR code of the lot
number, the product and the expiry date, printed from its own window.

**Android → Sell:** available lots are ordered by product and expiry. The earliest-expiring
sellable lot for each SKU has a **Sell first · FEFO** badge. Expired/recalled/blocked/exhausted
lots cannot be recommended. Unknown dates sort last; unknown SKU identity is not guessed.
The cashier may still choose another eligible lot. The same logic works on the cached catalog
and subtracts local pending sales before choosing the recommendation.

**Dashboard → Trace & recall:** expand **Recovery and customer follow-up** on a recall.
Each customer/lot has received, forwarded, returned and pending quantities. Contact statuses
are **Not contacted / Contacted / Customer confirmed**. Save a contact note with the supervisor
PIN. These notes are audited and do not move stock.

Use **Record physical return** only after receiving goods; this posts the actual return into
quarantine. The API caps it at received minus onward shipments minus earlier returns. If
Market C received 30 packs and forwarded 10 to the recorded restaurant, its responsibility is
20 and the restaurant's is 10. Totals are separate for kg/packs and never count the onward
delivery twice. A completed recovery does not release lot sale blocks. Unrecorded onward
sales cannot be inferred from the contact confirmation or from AI.

**Dashboard → Overview → Assessment report (PDF):** one click downloads a PDF with:

- URT-2026-045 material/service cost, actual packs, EKP, VKP, margin and cost breakdown;
- rejected PRC-2026-000 with both the 63.50 EUR and 3 kg findings, then PRC-2026-001 weights
  and actual cost allocation;
- ET-2026-001 forward lot chain, the production output's backward suppliers, recorded market
  deliveries/contacts and any recorded recall recovery progress;
- model/reference metrics and sample predictions, explicitly labelled synthetic data.

The API reads the report on a single repeatable-read database snapshot. Export never starts a
recall or changes stock. A missing scenario record or unavailable API produces a visible error
instead of an incomplete PDF. Turkish text and the Euro sign use bundled OFL Noto Sans fonts;
no customer data is sent to a PDF service. Default scenario IDs can be changed through the
API query parameters documented in API.md.

**Dashboard → Alerts and the sidebar bell:** the bell counts open alerts (with the critical
ones), lots with 3 days or less to expiry and active recalls; each line opens its tab. Alerts are
written when a record is saved: a received order outside the yield forecast's 98 % band, a
butchery with unexpected waste or part weights, a purchase price more than 10 % above the
product's last three purchases, and a POS sale of a recalled or expired lot that the server
refused (the goods may already be with a customer). **Review** with the supervisor PIN marks an
alert under review or closes it as a real problem or a false alarm (note required, audited).
Closed alerts give the observed precision and confirmed loss per check. Alerts never change
stock, cost or lot status. To see one in the demo, record a spice receipt above 24.00 €/kg in
*Purchases*, or receive an order with far fewer packs than forecast.

**Alerts → accuracy and thresholds:** per check, the reviewed alerts give the precision and the
problems found by hand (**Record a missed problem**) the recall, each with its 95 % interval. The
**Alert thresholds** section lists each check's warning and critical value, what that value did in
the synthetic stress test (precision, recall, false alarms per 100 records; for prices, by price
volatility, next to the volatility observed in the recorded purchases) and who changed it last.
**Change** shows the test result for a typed value before saving with the supervisor PIN; new alerts
use it, older ones keep the values they were graded with. Background: docs/AI.md, *Calibrating the
live alerts without field data*.

**Dashboard → Trace & recall → Lot flow and Mass balance (forward trace):** the diagram draws the
lots by depth, the customers by distribution tier and the stock, till, waste and gap positions,
each arrow with its recorded quantity; a lot box opens the lot card, hover highlights its arrows.
The mass balance shows, per lot, where its quantity went (used in other lots, shipped, sold,
wasted, returned, in stock) and the undocumented gap, plus the final positions per unit: for
ET-2026-001, 100 kg went into URT-2026-045 and the 85 packs are at Markets A/B/C. A stock-count
write-off appears as a gap, because no document says where the goods went. The assessment PDF
carries the same table.

**Dashboard → Trace & recall → Recall drill:** tests the traceability of the lot on screen
without a real recall. **Start drill** (target time, default 4 hours, and a note; supervisor PIN)
traces the lot both ways and takes the mass balance as found; the clock starts. Each recorded
customer (tier 1 and recorded tier 2) is listed with contact, lots and quantity; **Reached** saves
the time it was reached. **Finish drill** shows the result before saving: **Passed** when every
quantity was documented, every customer was reached and the target time was kept, otherwise
**Failed** with the reasons, and a corrective action must be written. Nothing is blocked, no
stock moves and the drill never appears as a recall; the record cannot be changed afterwards.

**Dashboard → Lots & stock → Count stock:** pick a warehouse, type what is on the shelf for the
lots you counted (empty rows are not counted), give a reason for every difference and save with
the supervisor PIN. Each difference is booked as a count adjustment; *Recent counts* lists the
counts with their differences, reasons and value. If a lot's stock changed after the list was
opened (e.g. a till sale synced), the count is refused and the list refreshes. A shortage above
10 € (or any shortage of a recalled lot) raises an alert.

**Android → Lots → Receive goods from an invoice:** take a photo (or use the sample invoice),
type the supervisor PIN and the API reads it, exactly as for the dashboard. The lines come back as
an editable receipt: supplier code, receipt number, our lot number per line (never suggested),
SKU, supplier batch, quantity, price and expiry, with the match and the warnings per line. **Save
receipt** posts it with `POST /purchases`; the API's field errors show under the fields. Without
`NVIDIA_API_KEY` on the API the screen says reading is not set up. (Earlier builds saved the read
lines as local stock movements; the sync booked those as till sales or dropped them. That path is
gone.)

**Android → Lots → Stock count:** the shop's lots with the server's balance; type what is on the
shelf or scan packs (each scan counts one; weighed lots ask for the weight), give a reason for
every difference and save with the supervisor PIN (`POST /stock-counts`, warehouse `MAGAZA-01`).
Refused while sales are still unsent (they would look like a shortage and then reduce the stock a
second time) or offline; if a balance changed after the list was opened, the API refuses the count
and the list is reloaded.

**Dashboard → Sales → Void sale:** corrects a wrong POS sale (wrong product scanned, test sale)
by a reversal, never by deleting it. Type the reason and the supervisor PIN: each line gets a
`RETURN` movement back into the store, the void is written to the audit log with your name and
reason, the line stays in the list marked *Voided* and the revenue/profit totals leave it out. A
sale can be voided once. No new table and no migration. Customer returns of delivered goods still
use *Record physical return*.

**Dashboard → Purchases → New receipt → Read from invoice:** choose a photo of the supplier
invoice or delivery note (on a phone the camera opens), type the supervisor PIN and press **Read
invoice**. The browser shrinks the photo, the API sends it to the vision model with the key it
holds, and the form fills with the supplier, invoice number and lines (SKU, supplier batch,
quantity, price, expiry). A review table shows what was printed next to the matched record
(*Exact*, *Similar, check it*, *No match*) and what to check: a different unit, another currency,
a missing expiry. Nothing is saved until the supervisor has checked the lines, given our own lot
numbers and pressed **Save receipt**. Without `NVIDIA_API_KEY` the form says that reading is not
set up and lines are typed by hand. To switch it on for compose, put the key in a `.env` file next
to `docker-compose.yml` (`NVIDIA_API_KEY=nvapi-...`, gitignored) and rebuild `api-go`.

## Existing installation

New installations use schema.sql normally. Existing databases need the additive/procedure
migrations, preserving records (Git Bash example from the repository root):

```bash
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261029_production_dispatch_guard.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261029_recall_followup.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261030_prediction_origin.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261030_alert_inbox.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261030_stock_count.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261031_alert_rules.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261031_frozen_butchery_forecast.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261101_subcontractor_returns.sql
docker compose exec -T mysql mysql -uroot -pban --comments < database-mysql/migrations/20261101_recall_drill.sql
docker compose up -d --build api-go web-react
```

Rebuild/install the Android APK to get the FEFO badge and the lot scan. Updating the web/API does not update a
phone's installed app. No app installation or public release is performed by the test suite.

## Validation

- Go integration: 30 → 20 + 10 tier responsibility, contact vs physical return, operator
  authentication, append-only audit, closed-recall writes, return cap, report evidence,
  missing records, no stock side effects and a concurrent-change snapshot test. Recall drill:
  nothing blocked or moved, customers reached once, computed PASS/FAIL, findings required on a
  FAIL, immutable record and a refused forced PASS (`integration_recall_drill_test.go`,
  `database-mysql/tests/test_recall_drill.py`). Invoice reading: request to a fake vision
  service (server key, image data URI), upstream errors not passed on, refused images never reach
  the model, word matching against the seed suppliers/products with warnings, nothing written
  (`invoice_reader_test.go`, `integration_invoice_test.go`).
- Android: invoice receipt draft (4: suggestions to an editable receipt, required fields, the
  `POST /purchases` body) and stock count rules (5: as the dashboard, scanned packs, the
  `POST /stock-counts` body); five FEFO tests, five lot-scan tests (our label, GS1 with parentheses and with FNC1
  separators and a weight, no guess for a bare GTIN or an unknown AI, lookup by lot number, batch
  and recall list) plus the existing validation/security tests; lint and debug build. The camera
  scan itself needs a device and was not run here.
- Lot label: the printed QR is rasterised and read back by an independent decoder (`jsqr`)
  as the lot number (`lotLabel.test.js`); the browser test opens the label window.
- Browser: real PDF download, report failure without download, contact acknowledgement and
  physical return through the actual forms; a recall drill started, every customer reached and
  closed through the forms with the lot statuses unchanged; invoice reading switched off on the
  test API, then (vision answer mocked in the browser) a photo sent as a downscaled JPEG, the form
  filled, the unmatched line left without a guess and the receipt saved by the real API.
- `python scripts/verify-assessment-pdf.py`: extracts the exported PDF with pypdf and checks
  Turkish text, the required amounts, lots and market names. Optional `--preview` also needs
  PyMuPDF and renders two preview PNGs next to the PDF.
- CI runs the browser checks on Chromium with seeded MySQL/API and uploads the PDF and screenshots.

Local browser test (requires a seeded test API, does write a recall/return in that database):

```bash
cd web-react
E2E_API_BASE=http://127.0.0.1:18080 npm run test:e2e
# On Windows use E2E_BROWSER_CHANNEL=msedge to use an installed Edge browser.
```
