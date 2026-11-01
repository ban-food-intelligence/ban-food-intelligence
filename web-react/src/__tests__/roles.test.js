import { afterEach, describe, expect, it, vi } from 'vitest';

// The back-office session belongs to the PIN that opened it: a write is always made as the
// person who typed the PIN, with that person's role.
describe('apiWrite session per PIN', () => {
  afterEach(() => vi.unstubAllGlobals());
  const reply = (status, body) => ({ ok: status < 400, status, text: async () => JSON.stringify(body) });

  it('signs in again when another PIN is typed and reports the role', async () => {
    const users = {
      271828: { token: 'tok-op', username: 'uretim_sefi', display_name: 'Üretim Şefi', role: 'OPERATOR' },
      314159: { token: 'tok-q', username: 'kalite_uzmani', display_name: 'Kalite Uzmanı', role: 'QUALITY' },
    };
    const sent = [];
    vi.stubGlobal('fetch', async (url, opts) => {
      if (url.endsWith('/auth/session')) {
        const { pin } = JSON.parse(opts.body);
        return reply(200, { ...users[pin], expires_at: new Date(Date.now() + 600_000).toISOString() });
      }
      sent.push(opts.headers.Authorization);
      return reply(201, {});
    });
    vi.resetModules();
    const { apiWrite, currentSession } = await import('../lib/api.js');

    await apiWrite('/movements/waste', {}, '271828');
    expect(currentSession()).toEqual({ username: 'uretim_sefi', displayName: 'Üretim Şefi', role: 'OPERATOR' });
    await apiWrite('/recall', {}, '314159');
    expect(currentSession().role).toBe('QUALITY');
    await apiWrite('/recall', {}, '314159'); // same PIN: the session is reused
    expect(sent).toEqual(['Bearer tok-op', 'Bearer tok-q', 'Bearer tok-q']);
  });

  it('surfaces a 403 for a role that may not write here', async () => {
    vi.stubGlobal('fetch', async (url) => {
      if (url.endsWith('/auth/session')) {
        return reply(200, { token: 't', role: 'VIEWER', username: 'denetci', expires_at: new Date(Date.now() + 600_000).toISOString() });
      }
      return reply(403, { code: 'FORBIDDEN', message: 'Rolünüz bu işlem için yetkili değil.', details: { role: 'VIEWER', allowed_roles: ['ADMIN', 'QUALITY'] } });
    });
    vi.resetModules();
    const { apiWrite } = await import('../lib/api.js');
    await expect(apiWrite('/recall', {}, '161803')).rejects.toMatchObject({ code: 'FORBIDDEN', status: 403 });
  });
});
