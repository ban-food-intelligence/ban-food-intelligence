import React, { useEffect, useMemo, useState } from 'react';
import { apiWrite, hasSession, useApi } from '../lib/api';
import { useLang } from '../lib/i18n';
import { Async, ErrorState, Group, Kpi, Pill, Table } from '../lib/ui';
import { LotLink } from './LotPanel';

/** The result the API computes when the drill is closed after elapsedSeconds (same three rules). */
export function drillVerdict(drill, elapsedSeconds) {
  const reasons = [];
  if (!drill.balanced) reasons.push('UNEXPLAINED_QTY');
  if ((drill.contacts || []).some((c) => !c.reached_at)) reasons.push('CUSTOMER_NOT_REACHED');
  if (elapsedSeconds > drill.target_minutes * 60) reasons.push('OVER_TIME');
  return { result: reasons.length ? 'FAIL' : 'PASS', reasons };
}

/** 1:05:09 */
export function clock(seconds) {
  const s = Math.max(0, Math.floor(Number(seconds) || 0));
  const pad = (v) => String(v).padStart(2, '0');
  return `${Math.floor(s / 3600)}:${pad(Math.floor((s % 3600) / 60))}:${pad(s % 60)}`;
}

const POSITIONS = [['unexplained', 'dr_posGap'], ['at_customers', 'dr_posCustomers'], ['sold', 'dr_posSold'],
  ['in_stock', 'dr_posStock'], ['wasted', 'dr_posWasted']];

/** Where the traced quantity is, per unit, without the empty positions: [[textKey, qty, uom], ...]. */
export function drillPositions(totals) {
  return (totals || []).flatMap((x) => POSITIONS.filter(([field]) => Math.abs(x[field]) > 0.0005)
    .map(([field, key]) => [key, x[field], x.uom_code]));
}

/** Target minutes typed in the form: whole minutes from 1 to one week, else null. */
export function parseTarget(text) {
  const v = Number(String(text).trim());
  return Number.isInteger(v) && v >= 1 && v <= 10080 ? v : null;
}

function useNow(active) {
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    if (!active) return undefined;
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, [active]);
  return now;
}

/** A timed traceability test of the lot on screen: it blocks nothing and moves no stock. */
export default function RecallDrill({ lot }) {
  const { t } = useLang();
  const drills = useApi('/recall/drills');
  const [starting, setStarting] = useState(false);
  const [target, setTarget] = useState('240');
  const [note, setNote] = useState('');
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  // open drills tick on from the server's elapsed time at load, not from the browser clock
  const loadedAt = useMemo(() => Date.now(), [drills.data]);
  const now = useNow((drills.data || []).some((d) => d.status === 'OPEN'));
  const minutes = parseTarget(target);
  const needPin = !hasSession();

  const start = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await apiWrite('/recall/drills', { lot_no: lot, target_minutes: minutes, note: note.trim() }, pin);
      setStarting(false);
      setNote('');
      drills.reload();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
      setPin('');
    }
  };

  return (
    <Group
      title={t('dr_title')}
      footer={t('dr_note')}
      aside={!starting && (
        <button className="btn-primary no-print" onClick={() => { setStarting(true); setError(null); }} disabled={busy || !lot}>
          {t('dr_start', lot)}
        </button>
      )}
    >
      {starting && (
        <form className="confirm no-print" onSubmit={start}>
          <p>{t('dr_confirm', lot)}</p>
          <div className="toolbar">
            <label className="field">
              <span>{t('dr_targetLabel')}</span>
              <input inputMode="numeric" value={target} onChange={(e) => setTarget(e.target.value)} required />
              {minutes == null && <span className="field-error">{t('dr_badTarget')}</span>}
            </label>
            <label className="field field-wide">
              <span>{t('dr_noteLabel')}</span>
              <input value={note} maxLength={255} onChange={(e) => setNote(e.target.value)} />
            </label>
            {needPin && (
              <label className="field">
                <span>{t('supervisorPin')}</span>
                <input type="password" inputMode="numeric" autoComplete="off" maxLength={12} value={pin}
                  onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
              </label>
            )}
          </div>
          {error && <ErrorState error={error} />}
          <div className="confirm-actions">
            <button type="button" className="btn-text" disabled={busy} onClick={() => { setStarting(false); setPin(''); }}>{t('cancel')}</button>
            <button type="submit" className="btn-primary" disabled={busy || (needPin && pin.length < 4) || minutes == null}>{t('dr_begin')}</button>
          </div>
        </form>
      )}
      <Async result={drills} rows={2}>
        {(list) => (!list || list.length === 0 ? <p className="empty">{t('dr_none')}</p> : list.map((d) => (
          <Drill key={d.drill_no} drill={d} onChanged={drills.reload}
            elapsed={d.status === 'OPEN' ? d.elapsed_seconds + Math.max(0, now - loadedAt) / 1000 : d.elapsed_seconds} />
        )))}
      </Async>
    </Group>
  );
}

function Drill({ drill: d, elapsed, onChanged }) {
  const { t, fmt } = useLang();
  const [entry, setEntry] = useState(null); // { mode: 'contact', customer } or { mode: 'close' }
  const [text, setText] = useState('');
  const [pin, setPin] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const open = d.status === 'OPEN';
  const needPin = !hasSession(); // a supervisor session from an earlier action needs no PIN
  const verdict = open ? drillVerdict(d, elapsed) : { result: d.result, reasons: d.fail_reasons || [] };
  const over = elapsed > d.target_minutes * 60;
  const reasonText = (reasons) => reasons.map((r) => t(`dr_reason_${r}`)).join(' · ');
  const located = drillPositions(d.mass_balance_totals).map(([key, qty, uom]) => t(key, fmt.qty(qty, uom))).join(' · ');

  const edit = (next) => {
    setEntry(next);
    setText('');
    setPin('');
    setError(null);
  };
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (entry.mode === 'contact') {
        await apiWrite('/recall/drills/contact', { drill_no: d.drill_no, customer_code: entry.customer.customer_code, note: text.trim() }, pin);
      } else {
        await apiWrite('/recall/drills/close', { drill_no: d.drill_no, findings: text.trim() }, pin);
      }
      setEntry(null);
      onChanged();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
      setPin('');
    }
  };

  return (
    <div className="recall drill" data-drill={d.drill_no}>
      <div className="recall-head">
        <div>
          <strong>{d.drill_no}</strong> · <LotLink lot={d.lot_no} />
          <div className="cell-sub">{fmt.dateTime(d.started_at)} · {d.started_by}{d.note ? ` · ${d.note}` : ''}</div>
        </div>
        <div className="recall-actions">
          {open
            ? <Pill tone="neutral">{t('dr_open')}</Pill>
            : <Pill tone={d.result === 'PASS' ? 'ok' : 'bad'}>{t(d.result === 'PASS' ? 'dr_pass' : 'dr_fail')}</Pill>}
        </div>
      </div>
      <div className="kpi-row">
        <Kpi label={t(open ? 'dr_elapsed' : 'dr_duration')} value={clock(elapsed)} sub={t('dr_target', clock(d.target_minutes * 60))}
          tone={over ? 'bad' : open ? undefined : 'ok'} />
        <Kpi label={t('dr_located')} value={t(d.balanced ? 'dr_all' : 'dr_gap')} sub={located} tone={d.balanced ? 'ok' : 'bad'} />
        <Kpi label={t('dr_reached')} value={`${d.customers_reached} / ${d.contacts.length}`}
          tone={d.customers_reached === d.contacts.length ? 'ok' : 'warn'} />
        <Kpi label={t('dr_lots')} value={`${d.lots_forward} / ${d.lots_backward}`} sub={t('dr_lotsSub', d.suppliers)} />
        <Kpi label={t('dr_traceTime')} value={`${fmt.num(d.trace_ms, 0)} ms`} />
      </div>
      <Table
        rows={d.contacts}
        rowKey={(c) => c.customer_code}
        empty={t('dr_noCustomers')}
        columns={[
          { key: 'tier', label: t('tier'), align: 'num' },
          { key: 'customer', label: t('customer'), render: (c) => <>{c.customer_name}<div className="cell-sub">{c.customer_code}</div></> },
          { key: 'contact', label: t('contact'), render: (c) => <>{c.contact_person || '–'}<div className="cell-sub">{[c.phone, c.email].filter(Boolean).join(' · ')}</div></> },
          { key: 'lots', label: t('lot'), render: (c) => c.lots.map((l, i) => <React.Fragment key={l}>{i > 0 && ', '}<LotLink lot={l} /></React.Fragment>) },
          { key: 'qty', label: t('shipped'), align: 'num', render: (c) => c.quantities.map((q) => fmt.qty(q.qty, q.uom_code)).join(' + ') },
          {
            key: 'reached', label: t('dr_status'), render: (c) => (c.reached_at ? <>
              <Pill tone="ok">{t('dr_reachedAfter', clock(c.minutes_to_reach * 60))}</Pill>
              <div className="cell-sub">{c.reached_by} · {fmt.dateTime(c.reached_at)}</div>
              {c.note && <div className="cell-sub">{c.note}</div>}
            </> : open
              ? <button className="btn-text no-print" disabled={busy} onClick={() => edit({ mode: 'contact', customer: c })}>{t('dr_markReached')}</button>
              : <Pill tone="bad">{t('dr_notReached')}</Pill>),
          },
        ]}
      />
      {open && !entry && (
        <div className="confirm-actions drill-actions no-print">
          <button className="btn-primary" onClick={() => edit({ mode: 'close' })}>{t('dr_close')}</button>
        </div>
      )}
      {entry && (
        <form className="confirm no-print" onSubmit={submit}>
          <h3>{entry.mode === 'close' ? t('dr_close') : `${t('dr_markReached')} · ${entry.customer.customer_name}`}</h3>
          {entry.mode === 'close' && (
            <p className={`notice ${verdict.result === 'PASS' ? 'notice-ok' : 'notice-bad'}`}>
              {verdict.result === 'PASS' ? t('dr_willPass') : t('dr_willFail', reasonText(verdict.reasons))}
            </p>
          )}
          <div className="toolbar">
            <label className="field field-wide">
              <span>{entry.mode === 'close' ? t(verdict.result === 'PASS' ? 'dr_findingsOptional' : 'dr_findings') : t('dr_contactNote')}</span>
              <input value={text} maxLength={entry.mode === 'close' ? 1000 : 255} onChange={(e) => setText(e.target.value)} />
            </label>
            {needPin && (
              <label className="field">
                <span>{t('supervisorPin')}</span>
                <input type="password" inputMode="numeric" autoComplete="off" maxLength={12} value={pin}
                  onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))} />
              </label>
            )}
          </div>
          {error && <ErrorState error={error} />}
          <div className="confirm-actions">
            <button type="button" className="btn-text" disabled={busy} onClick={() => setEntry(null)}>{t('cancel')}</button>
            <button type="submit" className="btn-primary"
              disabled={busy || (needPin && pin.length < 4) || (entry.mode === 'close' && verdict.result === 'FAIL' && !text.trim())}>
              {entry.mode === 'close' ? t('dr_closeNow') : t('dr_saveReached')}
            </button>
          </div>
        </form>
      )}
      {!open && (
        <div className="drill-outcome">
          <p className="cell-sub">{t('dr_closedBy', fmt.dateTime(d.closed_at), d.closed_by)}</p>
          {verdict.reasons.length > 0 && <p className="tone-bad">{reasonText(verdict.reasons)}</p>}
          {d.findings && <p><strong>{t(d.result === 'PASS' ? 'dr_findingsPass' : 'dr_findings')}:</strong> {d.findings}</p>}
        </div>
      )}
    </div>
  );
}
