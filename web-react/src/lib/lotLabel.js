import QRCode from 'qrcode';

// Printable lot label: a QR code with the lot number itself, so the Android POS camera scanner
// and keyboard-wedge hand scanners read the same text a person would type.

const escapeHtml = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

/** QR code of text as an SVG (dark modules as one path, four-module quiet zone). */
export function qrSvg(text, sizeMm = 26) {
  const qr = QRCode.create(String(text), { errorCorrectionLevel: 'M' });
  const n = qr.modules.size;
  const quiet = 4;
  let d = '';
  for (let y = 0; y < n; y++) {
    for (let x = 0; x < n; x++) {
      if (qr.modules.get(y, x)) d += `M${x + quiet} ${y + quiet}h1v1h-1z`;
    }
  }
  const side = n + 2 * quiet;
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${side} ${side}" width="${sizeMm}mm" height="${sizeMm}mm" shape-rendering="crispEdges">`
    + `<rect width="${side}" height="${side}" fill="#fff"/><path d="${d}" fill="#000"/></svg>`;
}

/** The whole label page (60 x 40 mm), printed from its own window. */
export function lotLabelHtml({ lot, product, expiryText, expiryLabel, title }) {
  return `<!doctype html><html><head><meta charset="utf-8"><title>${escapeHtml(title)}</title><style>
@page { size: 60mm 40mm; margin: 0; }
* { box-sizing: border-box; }
body { margin: 0; font-family: system-ui, sans-serif; color: #000; background: #fff; }
.label { width: 60mm; height: 40mm; padding: 2mm; display: flex; gap: 2mm; align-items: center; }
.text { min-width: 0; }
.lot { font-family: ui-monospace, Consolas, monospace; font-weight: 700; font-size: 9.5pt; overflow-wrap: anywhere; }
.product { font-size: 8pt; margin-top: 1mm; }
.expiry { font-size: 8pt; margin-top: 1mm; }
</style></head><body><div class="label">${qrSvg(lot)}<div class="text">`
    + `<div class="lot">${escapeHtml(lot)}</div><div class="product">${escapeHtml(product)}</div>`
    + (expiryText ? `<div class="expiry">${escapeHtml(expiryLabel)}: ${escapeHtml(expiryText)}</div>` : '')
    + '</div></div></body></html>';
}

/** Opens the label in a new window and prints it; false when the browser blocked the window. */
export function printLotLabel(fields) {
  const w = window.open('', '_blank', 'width=420,height=320');
  if (!w) return false;
  w.document.open();
  w.document.write(lotLabelHtml(fields));
  w.document.close();
  w.focus();
  w.print(); // from here, not an inline script: works under a strict Content-Security-Policy
  return true;
}
