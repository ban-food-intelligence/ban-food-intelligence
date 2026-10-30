import React, { useState } from 'react';
import { API_BASE, apiWrite, useApi } from '../lib/api';
import { useLang } from '../lib/i18n';
import { Async, ErrorState, Group, Mono, Page, Pill, Segmented, Table } from '../lib/ui';
import RecallProgress from './RecallProgress';

export default function TraceRecall() {
  const { t } = useLang();
  const [input, setInput] = useState('ET-2026-001');
  const [lot, setLot] = useState('ET-2026-001');
  const [direction, setDirection] = useState('forward');
  const trace = useApi(lot ? `/trace/${encodeURIComponent(lot)}?direction=${direction}` : null);
  const recalls = useApi('/recall');

  return (
    <Page title={t('tr_title')} subtitle={t('tr_sub')}>
      <Group>
        <form
          className="toolbar"
          onSubmit={(e) => {
            e.preventDefault();
            setLot(input.trim().toUpperCase());
          }}
        >
          <label className="field">
            <span>{t('lotNo')}</span>
            <input value={input} onChange={(e) => setInput(e.target.value)} spellCheck={false} />
          </label>
          <Segmented
            label={t('tr_title')}
            value={direction}
            onChange={setDirection}
            options={[{ value: 'forward', label: t('forward') }, { value: 'backward', label: t('backward') }]}
          />
          <button className="btn-primary" type="submit">{t('show')}</button>
        </form>
      </Group>

      <Async result={trace}>{(tr) => <TraceResult tr={tr} />}</Async>

      <RecallPanel lot={lot} recalls={recalls} />
    </Page>
  );
}

function TraceResult({ tr }) {
  const { t, fmt } = useLang();
  return (
    <>
      <Group title={t('lotsReached')}>
        <Table
          rows={tr.lots}
          rowTone={(l) => (l.status === 'RECALLED' ? 'bad' : '')}
          columns={[
            { key: 'depth', label: t('depth'), align: 'num' },
            { key: 'lot_no', label: t('lot'), render: (l) => <><Mono>{l.lot_no}</Mono><div className="cell-sub">{l.product_name}</div></> },
            { key: 'origin_type', label: t('origin') },
            { key: 'qty', label: t('qty'), align: 'num', render: (l) => fmt.qty(l.qty, l.uom_code) },
            { key: 'supplier_name', label: t('supplier'), render: (l) => l.supplier_name || '–' },
            { key: 'status', label: t('status'), render: (l) => <Pill tone={l.status === 'RECALLED' ? 'bad' : 'neutral'}>{l.status}</Pill> },
          ]}
        />
      </Group>
      <Group title={t('links')}>
        <Table
          rows={tr.links}
          columns={[
            { key: 'document_no', label: t('document'), render: (l) => <><Mono>{l.document_no}</Mono><div className="cell-sub">{l.link_type}{l.subcontractor ? ` · ${l.subcontractor}` : ''}</div></> },
            { key: 'input_lot_no', label: t('inputLot'), render: (l) => <Mono>{l.input_lot_no}</Mono> },
            { key: 'qty_consumed', label: t('consumed'), align: 'num', render: (l) => fmt.qty(l.qty_consumed, l.input_uom) },
            { key: 'output_lot_no', label: t('outputLot2'), render: (l) => <Mono>{l.output_lot_no}</Mono> },
            { key: 'qty_produced', label: t('produced'), align: 'num', render: (l) => fmt.qty(l.qty_produced, l.output_uom) },
          ]}
        />
      </Group>
      {tr.direction === 'forward' && (
        <>
          <Group title={t('shipments')}>
            <Table
              rows={tr.shipments || []}
              columns={[
                { key: 'tier', label: t('tier'), align: 'num' },
                { key: 'customer_name', label: t('customer'), render: (s) => <>{s.customer_name}{s.received_via && <div className="cell-sub">{t('via')}: {s.received_via}</div>}</> },
                { key: 'contact', label: t('contact'), render: (s) => <>{s.contact_person}<div className="cell-sub">{s.phone} · {s.email}</div></> },
                { key: 'lot_no', label: t('lot'), render: (s) => <Mono>{s.lot_no}</Mono> },
                { key: 'shipment_no', label: t('document'), render: (s) => <><Mono>{s.shipment_no}</Mono><div className="cell-sub">{fmt.date(s.ship_date)}</div></> },
                { key: 'qty', label: t('qty'), align: 'num', render: (s) => <strong>{fmt.qty(s.qty, s.uom_code)}</strong> },
              ]}
            />
          </Group>
          <div className="grid-2">
            <Group title={t('stockNow')}>
              <Table
                rows={tr.stock || []}
                columns={[
                  { key: 'lot_no', label: t('lot'), render: (s) => <Mono>{s.lot_no}</Mono> },
                  { key: 'warehouse_code', label: t('warehouse'), render: (s) => <Mono>{s.warehouse_code}</Mono> },
                  { key: 'qty', label: t('qty'), align: 'num', render: (s) => fmt.qty(s.qty, s.uom_code) },
                ]}
              />
            </Group>
            <Group title={t('posSales')}>
              <Table
                rows={tr.sales || []}
                columns={[
                  { key: 'lot_no', label: t('lot'), render: (s) => <Mono>{s.lot_no}</Mono> },
                  { key: 'store_code', label: t('store') },
                  { key: 'qty_sold', label: t('qty'), align: 'num', render: (s) => fmt.num(s.qty_sold, 3) },
                ]}
              />
            </Group>
          </div>
        </>
      )}
    </>
  );
}

function RecallPanel({ lot, recalls }) {
  const { t, fmt } = useLang();
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState(null); // { ok, text } or { error }
  const [pin, setPin] = useState('');

  const start = async () => {
    setBusy(true);
    setOutcome(null);
    try {
      const res = await apiWrite('/recall', { trigger_lot: lot, reason: t('recallReason') }, pin);
      const s = res.recall_summary;
      setOutcome({ ok: true, text: t('recallStarted', s.recall_code, (s.affected_lots || []).length) });
      recalls.reload();
    } catch (error) {
      setOutcome({ error }); // real failure, never a fake success
    } finally {
      setBusy(false);
      setConfirming(false);
      setPin('');
    }
  };

  return (
    <Group
      title={t('recallTitle')}
      aside={
        !confirming && (
          <button className="btn-danger" onClick={() => setConfirming(true)} disabled={busy || !lot}>
            {t('startRecall', lot)}
          </button>
        )
      }
    >
      {confirming && (
        <div className="confirm">
          <p>{t('confirmRecall', lot)}</p>
          <label className="pin-field">
            <span>{t('supervisorPin')}</span>
            <input
              type="password"
              inputMode="numeric"
              autoComplete="off"
              maxLength={12}
              value={pin}
              onChange={(e) => setPin(e.target.value.replace(/\D/g, ''))}
            />
          </label>
          <p className="muted">{t('pinHint')}</p>
          <div className="confirm-actions">
            <button className="btn-text" onClick={() => setConfirming(false)}>{t('cancel')}</button>
            <button className="btn-danger" onClick={start} disabled={busy || pin.length < 4}>{t('confirm')}</button>
          </div>
        </div>
      )}
      {outcome?.ok && <p className="notice notice-ok">{outcome.text}</p>}
      {outcome?.error && (
        <>
          <p className="notice notice-bad">{t('recallFailed')}</p>
          <ErrorState error={outcome.error} />
        </>
      )}

      <Async result={recalls} rows={2}>
        {(list) =>
          !list || list.length === 0 ? (
            <p className="empty">{t('noRecall')}</p>
          ) : (
            list.map((rc) => (
              <div key={rc.recall_code} className="recall">
                <div className="recall-head">
                  <div>
                    <strong>{rc.recall_code}</strong> · <Mono>{rc.trigger_lot}</Mono>
                    <div className="cell-sub">{fmt.dateTime(rc.timestamp)} · {rc.reason}</div>
                  </div>
                  <div className="recall-actions">
                    {rc.lock_enforced && <Pill tone="bad">{t('lockEnforced')}</Pill>}
                    <a className="btn-text" href={`${API_BASE}/recall/contacts.csv?recall_code=${encodeURIComponent(rc.recall_code)}`}>{t('exportCsv')}</a>
                    <button className="btn-text no-print" onClick={() => window.print()}>{t('print')}</button>
                  </div>
                </div>
                <p className="cell-sub">{t('affectedLots')}: {(rc.affected_lots || []).join(', ')}</p>
                <Table
                  rows={rc.affected_markets || []}
                  columns={[
                    { key: 'tier', label: t('tier'), align: 'num' },
                    { key: 'customer_name', label: t('customer'), render: (m) => <>{m.customer_name}<div className="cell-sub">{m.customer_code}{m.received_via ? ` · ${t('via')}: ${m.received_via}` : ''}</div></> },
                    { key: 'contact', label: t('contact'), render: (m) => <>{m.contact_person}<div className="cell-sub">{m.phone} · {m.email}</div></> },
                    { key: 'address', label: t('address'), render: (m) => m.address || '–' },
                    { key: 'lots', label: t('lot'), render: (m) => <Mono>{m.lots}</Mono> },
                    { key: 'qty_shipped', label: t('shipped'), align: 'num', render: (m) => <strong>{fmt.num(m.qty_shipped, 0)}</strong> },
                    { key: 'qty_returned', label: t('returned'), align: 'num', render: (m) => fmt.num(m.qty_returned, 0) },
                    {
                      key: 'second_tier',
                      label: '',
                      render: (m) => (
                        <Pill tone={m.second_tier === 'RECORDED' ? 'neutral' : 'warn'}>
                          {m.second_tier === 'RECORDED' ? t('secondTierRecorded') : t('secondTierNotRecorded')}
                        </Pill>
                      ),
                    },
                  ]}
                />
                <RecallProgress recall={rc} onChanged={recalls.reload} />
              </div>
            ))
          )
        }
      </Async>
    </Group>
  );
}
