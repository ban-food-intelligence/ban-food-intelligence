import React, { useState } from 'react';
import { apiWrite, useApi } from '../lib/api';
import { useLang } from '../lib/i18n';
import { ErrorState, Group, Kpi, Mono, Pill, Segmented, Table } from '../lib/ui';

// Back-office entry forms: raw-material receipt (POST /purchases) and butchery operation
// (POST /butchery/operations). Both need the supervisor PIN; the API validates everything
// again and its per-field errors are shown next to the fields.

const today = () => new Date().toISOString().slice(0, 10);

/** "12,5" or "12.5" -> 12.5; empty -> NaN so the API reports it as missing. */
const parseNum = (v) => (String(v).trim() === '' ? NaN : Number(String(v).replace(',', '.')));
const toJsonNum = (v) => (Number.isNaN(parseNum(v)) ? null : parseNum(v));

function Field({ label, error, children, wide }) {
  return (
    <label className={`field ${wide ? 'field-wide' : ''}`}>
      <span>{label}</span>
      {children}
      {error && <span className="field-error">{error}</span>}
    </label>
  );
}

function CellInput({ error, ...props }) {
  return (
    <>
      <input className={`cell-input ${error ? 'invalid' : ''}`} aria-invalid={!!error} {...props} />
      {error && <div className="field-error">{error}</div>}
    </>
  );
}

/** PIN + submit row; the same rules as the recall confirmation. */
function SubmitBar({ pin, setPin, busy, onCancel, actions }) {
  const { t } = useLang();
  return (
    <div className="entry-submit">
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
      <div className="confirm-actions">
        <button type="button" className="btn-text" onClick={onCancel} disabled={busy}>{t('cancel')}</button>
        {actions}
      </div>
    </div>
  );
}

/** Splits an API error into per-field messages (422 INVALID_INPUT) and a general error. */
function useSubmit() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const [fieldErrors, setFieldErrors] = useState({});
  const run = async (fn) => {
    setBusy(true);
    setError(null);
    setFieldErrors({});
    try {
      return await fn();
    } catch (e) {
      if (e.code === 'INVALID_INPUT' && e.details && typeof e.details === 'object') setFieldErrors(e.details);
      setError(e);
      return null;
    } finally {
      setBusy(false);
    }
  };
  return { busy, error, fieldErrors, run };
}

const unique = (xs) => [...new Set(xs.filter(Boolean))].sort();

// ---------------------------------------------------------------- receipt

const emptyLine = () => ({ sku: '', lot_no: '', supplier_lot_no: '', qty: '', unit_price: '', expiry_date: '' });

export function ReceiptForm({ onDone, onCancel }) {
  const { t, fmt } = useLang();
  const purchases = useApi('/purchases');
  const lots = useApi('/lots');
  const [head, setHead] = useState({ receipt_no: '', supplier_code: '', receipt_date: today(), invoice_no: '' });
  const [lines, setLines] = useState([emptyLine()]);
  const [pin, setPin] = useState('');
  const [saved, setSaved] = useState(null);
  const { busy, error, fieldErrors: fe, run } = useSubmit();

  const suppliers = unique((purchases.data || []).map((p) => p.supplier_code));
  const supplierName = Object.fromEntries((purchases.data || []).map((p) => [p.supplier_code, p.supplier_name]));
  const skus = unique([...(lots.data || []).map((l) => l.sku), ...(purchases.data || []).map((p) => p.sku)]);
  const setLine = (i, k, v) => setLines((ls) => ls.map((l, j) => (j === i ? { ...l, [k]: v } : l)));
  const total = lines.reduce((a, l) => a + (parseNum(l.qty) * parseNum(l.unit_price) || 0), 0);

  const submit = async (e) => {
    e.preventDefault();
    const body = {
      ...head,
      receipt_no: head.receipt_no.trim().toUpperCase(),
      supplier_code: head.supplier_code.trim().toUpperCase(),
      lines: lines.map((l) => ({
        sku: l.sku.trim().toUpperCase(),
        lot_no: l.lot_no.trim().toUpperCase(),
        supplier_lot_no: l.supplier_lot_no.trim(),
        qty: toJsonNum(l.qty),
        unit_price: toJsonNum(l.unit_price),
        expiry_date: l.expiry_date,
      })),
    };
    const res = await run(() => apiWrite('/purchases', body, pin));
    if (res) {
      setSaved(res);
      setPin('');
      onDone?.();
    }
  };

  if (saved) {
    return (
      <Group title={t('en_receiptTitle')} aside={<Pill tone="ok">{t('posted')}</Pill>}>
        <p className="notice notice-ok">{t('en_receiptSaved', saved.receipt_no, fmt.money(saved.total))}</p>
        <Table
          rows={saved.lines}
          columns={[
            { key: 'lot_no', label: t('lot'), render: (l) => <Mono>{l.lot_no}</Mono> },
            { key: 'sku', label: t('product'), render: (l) => <Mono>{l.sku}</Mono> },
            { key: 'qty', label: t('qty'), align: 'num', render: (l) => fmt.num(l.qty, 3) },
            { key: 'line_total', label: t('amount'), align: 'num', render: (l) => fmt.money(l.line_total) },
            { key: 'expiry_date', label: t('expiry'), render: (l) => fmt.date(l.expiry_date) },
          ]}
        />
        <div className="confirm-actions entry-pad">
          <button className="btn-text" onClick={onCancel}>{t('en_close')}</button>
        </div>
      </Group>
    );
  }

  return (
    <Group title={t('en_receiptTitle')} footer={t('en_receiptNote')}>
      <form onSubmit={submit} noValidate>
        <div className="toolbar">
          <Field label={t('receipt')} error={fe.receipt_no}>
            <input value={head.receipt_no} onChange={(e) => setHead({ ...head, receipt_no: e.target.value })} placeholder="ALS-2026-…" spellCheck={false} required />
          </Field>
          <Field label={t('supplier')} error={fe.supplier_code}>
            <input list="en-suppliers" value={head.supplier_code} onChange={(e) => setHead({ ...head, supplier_code: e.target.value })} spellCheck={false} required />
            <datalist id="en-suppliers">
              {suppliers.map((s) => <option key={s} value={s}>{supplierName[s]}</option>)}
            </datalist>
          </Field>
          <Field label={t('date')} error={fe.receipt_date}>
            <input type="date" value={head.receipt_date} onChange={(e) => setHead({ ...head, receipt_date: e.target.value })} required />
          </Field>
          <Field label={t('invoice')}>
            <input value={head.invoice_no} onChange={(e) => setHead({ ...head, invoice_no: e.target.value })} spellCheck={false} />
          </Field>
        </div>

        <datalist id="en-skus">{skus.map((s) => <option key={s} value={s} />)}</datalist>
        <div className="table-scroll">
          <table className="table entry-lines">
            <thead>
              <tr>
                <th>{t('product')} (SKU)</th>
                <th>{t('lot')}</th>
                <th>{t('supplierLot')}</th>
                <th className="num">{t('qty')}</th>
                <th className="num">{t('unitPrice')}</th>
                <th>{t('expiry')}</th>
                <th className="num">{t('amount')}</th>
                <th />
              </tr>
            </thead>
            <tbody>
              {lines.map((l, i) => {
                const k = `lines[${i}].`;
                const amount = parseNum(l.qty) * parseNum(l.unit_price);
                return (
                  <tr key={i}>
                    <td><CellInput list="en-skus" value={l.sku} onChange={(e) => setLine(i, 'sku', e.target.value)} error={fe[`${k}sku`]} aria-label={t('product')} spellCheck={false} /></td>
                    <td><CellInput value={l.lot_no} onChange={(e) => setLine(i, 'lot_no', e.target.value)} error={fe[`${k}lot_no`]} aria-label={t('lot')} spellCheck={false} /></td>
                    <td><CellInput value={l.supplier_lot_no} onChange={(e) => setLine(i, 'supplier_lot_no', e.target.value)} error={fe[`${k}supplier_lot_no`]} aria-label={t('supplierLot')} spellCheck={false} /></td>
                    <td className="num"><CellInput inputMode="decimal" value={l.qty} onChange={(e) => setLine(i, 'qty', e.target.value)} error={fe[`${k}qty`]} aria-label={t('qty')} /></td>
                    <td className="num"><CellInput inputMode="decimal" value={l.unit_price} onChange={(e) => setLine(i, 'unit_price', e.target.value)} error={fe[`${k}unit_price`]} aria-label={t('unitPrice')} /></td>
                    <td><CellInput type="date" value={l.expiry_date} onChange={(e) => setLine(i, 'expiry_date', e.target.value)} error={fe[`${k}expiry_date`]} aria-label={t('expiry')} /></td>
                    <td className="num">{Number.isNaN(amount) ? '–' : fmt.money(amount)}</td>
                    <td>
                      {lines.length > 1 && (
                        <button type="button" className="btn-text muted" onClick={() => setLines((ls) => ls.filter((_, j) => j !== i))} aria-label={t('en_removeLine')}>×</button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={6}>
                  <button type="button" className="btn-text" onClick={() => setLines((ls) => [...ls, emptyLine()])}>{t('en_addLine')}</button>
                </td>
                <td className="num">{fmt.money(total)}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>
        {fe.lines && <p className="field-error entry-pad">{fe.lines}</p>}

        {error && <ErrorState error={error} />}
        <SubmitBar
          pin={pin}
          setPin={setPin}
          busy={busy}
          onCancel={onCancel}
          actions={<button type="submit" className="btn-primary" disabled={busy || pin.length < 4}>{t('en_saveReceipt')}</button>}
        />
      </form>
    </Group>
  );
}

// ---------------------------------------------------------------- butchery

const KINDS = ['CUT', 'BY_PRODUCT', 'WASTE'];
const emptyOutput = (kind = 'CUT') => ({ output_kind: kind, sku: '', weight_kg: '', lot_no: '' });
const TOLERANCE_KG = 0.01; // same 10 g tolerance as the API weight gate

export function ButcheryForm({ onDone, onCancel }) {
  const { t, fmt } = useLang();
  const lots = useApi('/lots');
  const [head, setHead] = useState({
    op_no: '', input_lot_no: '', input_weight_kg: '', allocation_method: 'VALUE', op_date: today(),
    operator_name: '', additional_cost: '', additional_cost_note: '',
  });
  const [outputs, setOutputs] = useState([emptyOutput(), emptyOutput('WASTE')]);
  const [pin, setPin] = useState('');
  const [result, setResult] = useState(null); // API response (DRY_RUN or POSTED)
  const { busy, error, fieldErrors: fe, run } = useSubmit();

  const kgLots = (lots.data || []).filter((l) => (l.uom_code || '').toUpperCase() === 'KG' && l.qty > 0);
  const inputLots = unique(kgLots.map((l) => l.lot_no));
  const skus = unique((lots.data || []).filter((l) => (l.uom_code || '').toUpperCase() === 'KG').map((l) => l.sku));
  const setOut = (i, k, v) => {
    setResult(null);
    setOutputs((os) => os.map((o, j) => (j === i ? { ...o, [k]: v } : o)));
  };
  const setH = (k, v) => {
    setResult(null);
    setHead((h) => ({ ...h, [k]: v }));
  };

  const input = parseNum(head.input_weight_kg);
  const outSum = outputs.reduce((a, o) => a + (parseNum(o.weight_kg) || 0), 0);
  const diff = Number.isNaN(input) ? NaN : input - outSum;
  const balanced = !Number.isNaN(diff) && Math.abs(diff) <= TOLERANCE_KG;

  const send = async (dryRun) => {
    const body = {
      op_no: head.op_no.trim().toUpperCase(),
      input_lot_no: head.input_lot_no.trim().toUpperCase(),
      input_weight_kg: toJsonNum(head.input_weight_kg),
      allocation_method: head.allocation_method,
      op_date: head.op_date,
      operator_name: head.operator_name.trim(),
      additional_cost: toJsonNum(head.additional_cost) ?? 0,
      additional_cost_note: head.additional_cost_note.trim(),
      dry_run: dryRun,
      outputs: outputs.map((o) => ({
        output_kind: o.output_kind,
        sku: o.sku.trim().toUpperCase(),
        weight_kg: toJsonNum(o.weight_kg),
        lot_no: o.output_kind === 'WASTE' ? '' : o.lot_no.trim().toUpperCase(),
      })),
    };
    const res = await run(() => apiWrite('/butchery/operations', body, pin));
    if (res) {
      setResult(res);
      if (!dryRun) {
        setPin('');
        onDone?.();
      }
    }
  };

  const posted = result?.status === 'POSTED';
  const wc = error?.code === 'WEIGHT_IMBALANCE' ? error.details : result?.weight_check;

  return (
    <Group title={t('en_butcheryTitle')} footer={t('en_butcheryNote')} aside={posted ? <Pill tone="ok">{t('posted')}</Pill> : null}>
      <form onSubmit={(e) => { e.preventDefault(); send(false); }} noValidate>
        <fieldset disabled={posted} className="entry-fieldset">
          <div className="toolbar">
            <Field label={t('en_opNo')} error={fe.op_no}>
              <input value={head.op_no} onChange={(e) => setH('op_no', e.target.value)} placeholder="PRC-2026-…" spellCheck={false} />
            </Field>
            <Field label={t('inputLot')} error={fe.input_lot_no}>
              <input list="en-input-lots" value={head.input_lot_no} onChange={(e) => setH('input_lot_no', e.target.value)} spellCheck={false} />
              <datalist id="en-input-lots">
                {inputLots.map((l) => <option key={l} value={l} />)}
              </datalist>
            </Field>
            <Field label={`${t('input')} (kg)`} error={fe.input_weight_kg}>
              <input inputMode="decimal" value={head.input_weight_kg} onChange={(e) => setH('input_weight_kg', e.target.value)} />
            </Field>
            <Field label={t('date')} error={fe.op_date}>
              <input type="date" value={head.op_date} onChange={(e) => setH('op_date', e.target.value)} />
            </Field>
            <Field label={t('en_operator')}>
              <input value={head.operator_name} onChange={(e) => setH('operator_name', e.target.value)} />
            </Field>
            <Field label={t('en_additionalCost')} error={fe.additional_cost}>
              <input inputMode="decimal" value={head.additional_cost} onChange={(e) => setH('additional_cost', e.target.value)} placeholder="0" />
            </Field>
            {parseNum(head.additional_cost) > 0 && (
              <Field label={t('en_additionalCostNote')} error={fe.additional_cost_note}>
                <input value={head.additional_cost_note} onChange={(e) => setH('additional_cost_note', e.target.value)} />
              </Field>
            )}
          </div>
          <div className="toolbar entry-tight">
            <Segmented
              label={t('allocationPreview')}
              value={head.allocation_method}
              onChange={(v) => setH('allocation_method', v)}
              options={[{ value: 'VALUE', label: t('methodValue') }, { value: 'QUANTITY', label: t('methodQty') }]}
            />
            {fe.allocation_method && <span className="field-error">{fe.allocation_method}</span>}
          </div>

          <datalist id="en-cut-skus">{skus.map((s) => <option key={s} value={s} />)}</datalist>
          <div className="table-scroll">
            <table className="table entry-lines">
              <thead>
                <tr>
                  <th>{t('en_kind')}</th>
                  <th>{t('product')} (SKU)</th>
                  <th className="num">{t('qty')} (kg)</th>
                  <th>{t('en_outputLot')}</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {outputs.map((o, i) => {
                  const k = `outputs[${i}].`;
                  return (
                    <tr key={i}>
                      <td>
                        <select className="cell-input" value={o.output_kind} onChange={(e) => setOut(i, 'output_kind', e.target.value)} aria-label={t('en_kind')}>
                          {KINDS.map((kind) => <option key={kind} value={kind}>{t(`en_kind_${kind}`)}</option>)}
                        </select>
                        {fe[`${k}output_kind`] && <div className="field-error">{fe[`${k}output_kind`]}</div>}
                      </td>
                      <td><CellInput list="en-cut-skus" value={o.sku} onChange={(e) => setOut(i, 'sku', e.target.value)} error={fe[`${k}sku`]} aria-label={t('product')} spellCheck={false} /></td>
                      <td className="num"><CellInput inputMode="decimal" value={o.weight_kg} onChange={(e) => setOut(i, 'weight_kg', e.target.value)} error={fe[`${k}weight_kg`]} aria-label={t('qty')} /></td>
                      <td>
                        {o.output_kind === 'WASTE' ? (
                          <span className="cell-sub">{t('en_noLotForWaste')}</span>
                        ) : (
                          <CellInput value={o.lot_no} onChange={(e) => setOut(i, 'lot_no', e.target.value)} error={fe[`${k}lot_no`]} aria-label={t('en_outputLot')} spellCheck={false} />
                        )}
                      </td>
                      <td>
                        {outputs.length > 1 && (
                          <button type="button" className="btn-text muted" onClick={() => { setResult(null); setOutputs((os) => os.filter((_, j) => j !== i)); }} aria-label={t('en_removeLine')}>×</button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
              <tfoot>
                <tr>
                  <td colSpan={2}>
                    <button type="button" className="btn-text" onClick={() => { setResult(null); setOutputs((os) => [...os, emptyOutput()]); }}>{t('en_addOutput')}</button>
                  </td>
                  <td className="num">{fmt.qty(outSum, 'KG')}</td>
                  <td colSpan={2} />
                </tr>
              </tfoot>
            </table>
          </div>
          {fe.outputs && <p className="field-error entry-pad">{fe.outputs}</p>}
        </fieldset>

        <div className="kpi-row">
          <Kpi label={t('input')} value={fmt.qty(Number.isNaN(input) ? null : input, 'KG')} />
          <Kpi label={t('en_outputsTotal')} value={fmt.qty(outSum, 'KG')} />
          <Kpi
            label={t('unrecorded')}
            value={fmt.qty(Number.isNaN(diff) ? null : diff, 'KG')}
            tone={Number.isNaN(diff) ? undefined : balanced ? 'ok' : 'bad'}
            sub={`${t('tolerance')} ±${fmt.qty(TOLERANCE_KG, 'KG')}`}
          />
        </div>

        {wc && error?.code === 'WEIGHT_IMBALANCE' && (
          <p className="notice notice-bad">{t('en_imbalance', fmt.qty(wc.unrecorded_kg, 'KG'))}</p>
        )}
        {error && error.code !== 'WEIGHT_IMBALANCE' && <ErrorState error={error} />}
        {result && <ButcheryResult res={result} />}

        {!posted && (
          <SubmitBar
            pin={pin}
            setPin={setPin}
            busy={busy}
            onCancel={onCancel}
            actions={
              <>
                <button type="button" className="btn-text" onClick={() => send(true)} disabled={busy || pin.length < 4}>{t('en_check')}</button>
                <button type="submit" className="btn-primary" disabled={busy || pin.length < 4 || !balanced}>{t('en_saveButchery')}</button>
              </>
            }
          />
        )}
        {posted && (
          <div className="confirm-actions entry-pad">
            <button type="button" className="btn-text" onClick={onCancel}>{t('en_close')}</button>
          </div>
        )}
      </form>
    </Group>
  );
}

function ButcheryResult({ res }) {
  const { t, fmt } = useLang();
  const a = res.allocation || {};
  return (
    <>
      <p className={`notice ${res.status === 'POSTED' ? 'notice-ok' : ''}`}>
        {res.status === 'POSTED' ? t('en_butcherySaved', res.op_no, fmt.money(res.total_cost)) : t('en_dryRunOk', fmt.money(res.total_cost))}
      </p>
      <Table
        rows={a.lines || []}
        rowTone={(l) => (l.output_kind === 'WASTE' ? 'muted' : '')}
        columns={[
          { key: 'sku', label: t('product'), render: (l) => <><Mono>{l.sku}</Mono><div className="cell-sub"><Mono>{l.lot_no || '–'}</Mono></div></> },
          { key: 'w', label: t('qty'), align: 'num', render: (l) => fmt.qty(l.weight_kg, 'KG') },
          { key: 'a', label: t('allocated'), align: 'num', render: (l) => <strong>{fmt.money(l.allocated_cost)}</strong> },
          { key: 'u', label: t('unitCost'), align: 'num', render: (l) => (l.output_kind === 'WASTE' ? '–' : `${fmt.money(l.unit_cost_per_kg)}/kg`) },
        ]}
        footer={
          <tr>
            <td colSpan={2}>{t('total')} · {a.method === 'QUANTITY' ? t('methodQty') : t('methodValue')}</td>
            <td className="num"><strong>{fmt.money(a.allocated_total)}</strong></td>
            <td className={a.sums_exact ? 'pos' : 'neg'}>{a.sums_exact ? t('sumsExact', fmt.money(res.total_cost)) : '≠'}</td>
          </tr>
        }
      />
    </>
  );
}
