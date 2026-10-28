import { afterEach, describe, expect, it, vi } from 'vitest';
import { DICT } from '../lib/i18n.jsx';
import { apiWrite } from '../lib/api.js';

describe('i18n', () => {
  it('has the same keys in Turkish and English', () => {
    expect(Object.keys(DICT.en).sort()).toEqual(Object.keys(DICT.tr).sort());
  });
  it('has no empty texts', () => {
    for (const lang of ['tr', 'en']) {
      for (const [k, v] of Object.entries(DICT[lang])) {
        if (typeof v === 'string') expect(v.trim(), `${lang}.${k}`).not.toBe('');
      }
    }
  });
});

describe('apiWrite (supervisor session)', () => {
  afterEach(() => vi.unstubAllGlobals());

  const reply = (status, body) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) });

  it('gets a token with the PIN, then sends the write with it', async () => {
    const calls = [];
    vi.stubGlobal('fetch', async (url, opts) => {
      calls.push({ url, opts });
      if (url.endsWith('/auth/session')) {
        return reply(200, { token: 'tok-1', expires_at: new Date(Date.now() + 600_000).toISOString() });
      }
      return reply(201, { recall_summary: { recall_code: 'RCL-1', affected_lots: ['ET-2026-001'] } });
    });
    const res = await apiWrite('/recall', { trigger_lot: 'ET-2026-001' }, '246813');
    expect(res.recall_summary.recall_code).toBe('RCL-1');
    expect(JSON.parse(calls[0].opts.body)).toEqual({ pin: '246813' });
    expect(calls[1].opts.headers.Authorization).toBe('Bearer tok-1');
    expect(calls[1].opts.body).not.toContain('246813'); // the PIN only goes to /auth/session
  });

  it('surfaces a wrong PIN as an error and sends no write', async () => {
    const calls = [];
    vi.stubGlobal('fetch', async (url) => {
      calls.push(url);
      return reply(401, { code: 'INVALID_PIN', message: 'Wrong PIN.', details: { attempts_left: 4 } });
    });
    // A fresh module has no session yet.
    vi.resetModules();
    const { apiWrite: write } = await import('../lib/api.js');
    await expect(write('/recall', {}, '000000')).rejects.toMatchObject({ code: 'INVALID_PIN', status: 401 });
    expect(calls.every((u) => u.endsWith('/auth/session'))).toBe(true);
  });
});
