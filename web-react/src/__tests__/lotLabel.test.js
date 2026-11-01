import jsQR from 'jsqr';
import { describe, expect, it } from 'vitest';
import { lotLabelHtml, qrSvg } from '../lib/lotLabel.js';

/** Rasterises the label's SVG path (one "Mx yh1v1h-1z" square per dark module) like a printer would. */
function rasterise(svg, scale = 6) {
  const side = Number(svg.match(/viewBox="0 0 (\d+) \d+"/)[1]);
  const px = side * scale;
  const rgba = new Uint8ClampedArray(px * px * 4).fill(255);
  for (const [, x, y] of svg.matchAll(/M(\d+) (\d+)h1v1h-1z/g)) {
    for (let dy = 0; dy < scale; dy++) {
      for (let dx = 0; dx < scale; dx++) {
        const i = ((Number(y) * scale + dy) * px + Number(x) * scale + dx) * 4;
        rgba[i] = rgba[i + 1] = rgba[i + 2] = 0;
      }
    }
  }
  return { rgba, px };
}

describe('lot label', () => {
  it('prints a QR code that an independent decoder reads back as the lot number', () => {
    for (const lot of ['SUC-2026-045', 'KZB-2026-001', 'ET-E2E-IV-1793535236406']) {
      const { rgba, px } = rasterise(qrSvg(lot));
      expect(jsQR(rgba, px, px)?.data).toBe(lot);
    }
  });

  it('escapes the texts it prints', () => {
    const html = lotLabelHtml({ lot: 'A<1>', product: 'Sucuk & "baharat"', expiryText: '08.11.2026', expiryLabel: 'SKT', title: 'Etiket' });
    expect(html).toContain('A&lt;1&gt;');
    expect(html).toContain('Sucuk &amp; &quot;baharat&quot;');
    expect(html).toContain('SKT: 08.11.2026');
    expect(html).not.toContain('<script');
    expect(lotLabelHtml({ lot: 'X', product: 'Y', expiryText: '', expiryLabel: 'SKT', title: 'T' })).not.toContain('SKT:');
  });
});
