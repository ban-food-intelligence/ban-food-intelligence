import React, { useState } from 'react';
import { apiWrite, useApi } from '../lib/api';
import { useLang } from '../lib/i18n';
import { Async, ErrorState, Group, Page, Pill, Segmented, Table } from '../lib/ui';
import { Field, SubmitBar, useSubmit } from './Entry';

const PATHS = { products: '/products', units: '/units', suppliers: '/suppliers', customers: '/customers', recipes: '/butchery/recipes' };
const PRODUCT_TYPES = ['RAW_MATERIAL', 'FINISHED_GOOD', 'CARCASS', 'CUT', 'BY_PRODUCT', 'WASTE'];
const PARTNER_TYPES = { suppliers: ['RAW_MATERIAL', 'SUBCONTRACTOR', 'LIVESTOCK'], customers: ['MARKET', 'WHOLESALER', 'RESTAURANT', 'WALK_IN'] };
const numeric = (v) => v === '' || v == null ? null : Number(String(v).replace(',', '.'));
const newLine = () => ({ sku: '', output_kind: 'CUT', expected_yield_pct: '', value_price_per_kg: '' });
const blank = (kind) => ({ code: '', name: '', ...(kind === 'products'
  ? { sku: '', product_type: 'RAW_MATERIAL', uom_code: 'KG', pack_size_kg: '', species: '', standard_purchase_price: '', sale_price: '', shelf_life_days: '', is_active: true }
  : kind === 'units' ? { is_weight: false }
    : kind === 'recipes' ? { species: 'KUZU', default_allocation: 'VALUE', lines: [newLine()] }
      : { type: PARTNER_TYPES[kind][0], contact_person: '', phone: '', email: '', address: '' }) });

export default function MasterData() {
  const { t, fmt } = useLang();
  const products = useApi(PATHS.products);
  const units = useApi(PATHS.units);
  const suppliers = useApi(PATHS.suppliers);
  const customers = useApi(PATHS.customers);
  const recipes = useApi(PATHS.recipes);
  const results = { products, units, suppliers, customers, recipes };
  const [kind, setKind] = useState('products');
  const [editor, setEditor] = useState(null);
  const [saved, setSaved] = useState(false);
  const edit = (record, update = true) => {
    setSaved(false);
    setEditor({ record: record || blank(kind), update: !!record && update });
  };
  const rowActions = (row) => kind === 'units' ? null : (
    <button className="btn-text" onClick={() => edit(kind === 'recipes' ? { ...row, code: '' } : row, kind !== 'recipes')}>
      {t(kind === 'recipes' ? 'md_version' : 'md_edit')}
    </button>
  );
  const columns = [
    { key: 'code', label: t('md_code'), render: (r) => <span className="mono">{r.sku || r.code}</span> },
    { key: 'name', label: t('md_name') },
    ...(kind === 'products' ? [
      { key: 'product_type', label: t('md_type'), render: (r) => t(`md_${r.product_type}`) },
      { key: 'uom_code', label: t('md_unit') },
      { key: 'standard_purchase_price', label: t('md_purchasePrice'), align: 'num', render: (r) => fmt.money(r.standard_purchase_price) },
      { key: 'sale_price', label: t('md_salePrice'), align: 'num', render: (r) => fmt.money(r.sale_price) },
      { key: 'is_active', label: t('status'), render: (r) => <Pill tone={r.is_active ? 'ok' : 'warn'}>{t(r.is_active ? 'md_active' : 'md_inactive')}</Pill> },
      { key: 'lots', label: t('md_uses'), render: (r) => t('md_lots', r.lots) },
    ] : kind === 'units' ? [
      { key: 'is_weight', label: t('md_weight'), render: (r) => r.is_weight ? '✓' : '–' },
    ] : kind === 'recipes' ? [
      { key: 'species', label: t('md_species') },
      { key: 'key', label: t('md_key'), render: (r) => t(r.default_allocation === 'VALUE' ? 'methodValue' : 'methodQty') },
      { key: 'lines', label: t('product'), render: (r) => <details><summary>{r.lines.length} · {t('md_operations', r.operations)}</summary>
        {r.lines.map((l) => <div key={l.sku}>{l.sku} · {t(`md_${l.output_kind}`)} · {fmt.pct(l.expected_yield_pct)} · {fmt.money(l.value_price_per_kg)}</div>)}
      </details> },
    ] : [
      { key: 'type', label: t('md_type'), render: (r) => t(`md_${r.type}`) },
      { key: 'contact_person', label: t('md_contact') }, { key: 'phone', label: t('md_phone') }, { key: 'email', label: t('md_email') },
      { key: 'documents', label: t('md_uses'), render: (r) => t('md_docs', r.documents) },
    ]),
    { key: 'actions', label: '', render: rowActions },
  ];
  return (
    <Page title={t('md_title')} subtitle={t('md_sub')} actions={!editor && <button className="btn-primary" onClick={() => edit(null)}>{t('md_new')}</button>}>
      <div className="master-tabs"><Segmented label={t('md_title')} value={kind} options={Object.keys(PATHS).map((k) => ({ value: k, label: t(`md_${k}`) }))}
        onChange={(k) => { setKind(k); setEditor(null); setSaved(false); }} /></div>
      {saved && <p className="notice notice-ok" role="status">{t('md_saved')}</p>}
      {editor && <MasterEditor key={`${kind}:${editor.record.sku || editor.record.code}:${editor.update}`} kind={kind} {...editor} products={products} units={units}
        onCancel={() => setEditor(null)} onSaved={() => { setEditor(null); setSaved(true); results[kind].reload(); }} />}
      <Group title={t(`md_${kind}`)} footer={t(kind === 'products' ? 'md_productLock' : kind === 'suppliers' ? 'md_supplierLock' : kind === 'recipes' || kind === 'units' ? 'md_immutable' : 'md_sub')}>
        <Async result={results[kind]}>{(rows) => <Table rows={rows} columns={columns} rowKey={(r) => r.sku || r.code} />}</Async>
      </Group>
    </Page>
  );
}

function MasterEditor({ kind, record, update, products, units, onCancel, onSaved }) {
  const { t } = useLang();
  const [f, setF] = useState(record);
  const [pin, setPin] = useState('');
  const { run, busy, error, fieldErrors: fe } = useSubmit();
  const set = (key, value) => setF((s) => ({ ...s, [key]: value }));
  const text = (key, label, props = {}) => <Field label={t(label)} error={fe[key]} key={key}>
    <input value={f[key] ?? ''} onChange={(e) => set(key, e.target.value)} {...(props.inputMode === 'decimal' ? { pattern: '[0-9]+([.,][0-9]+)?' } : {})} {...props} />
  </Field>;
  const select = (key, label, options, disabled = false) => <Field label={t(label)} error={fe[key]} key={key}>
    <select value={f[key]} onChange={(e) => set(key, e.target.value)} disabled={disabled} required>{options.map(([v, name]) => <option key={v} value={v}>{name}</option>)}</select>
  </Field>;
  const lineSet = (i, key, value) => set('lines', f.lines.map((l, j) => i === j ? { ...l, [key]: value } : l));
  const submit = async (e) => {
    e.preventDefault();
    let body = { ...f };
    if (kind === 'products') {
      ['pack_size_kg', 'standard_purchase_price', 'sale_price', 'shelf_life_days'].forEach((key) => { body[key] = numeric(f[key]); });
      body.species = f.species || null;
    }
    if (kind === 'recipes') body.lines = f.lines.map((l) => ({ ...l, expected_yield_pct: numeric(l.expected_yield_pct), value_price_per_kg: l.output_kind === 'WASTE' ? null : numeric(l.value_price_per_kg) }));
    const res = await run(() => apiWrite(PATHS[kind] + (update ? '/update' : ''), body, pin));
    if (res) onSaved();
  };
  const types = kind === 'products' ? PRODUCT_TYPES : PARTNER_TYPES[kind];
  const locked = update && (kind === 'products' ? f.lots > 0 || f.recipes > 0 : kind === 'suppliers' && f.documents > 0);
  const dependencyError = kind === 'products' ? units.error : kind === 'recipes' ? products.error : null;
  return (
    <Group title={t(update ? 'md_edit' : 'md_new')} footer={kind === 'recipes' ? t('md_yieldNote') : null}>
      <form onSubmit={submit}>
        <fieldset className="entry-fieldset" disabled={busy}>
          <div className="toolbar">
            {text(kind === 'products' ? 'sku' : 'code', 'md_code', { required: true, disabled: update, maxLength: kind === 'units' ? 10 : kind === 'products' || kind === 'recipes' ? 30 : 20, spellCheck: false })}
            {text('name', 'md_name', { required: true, maxLength: kind === 'units' ? 50 : kind === 'recipes' ? 100 : 150 })}
            {types && select(kind === 'products' ? 'product_type' : 'type', 'md_type', types.map((v) => [v, t(`md_${v}`)]), locked)}
            {kind === 'products' && <>
              {select('uom_code', 'md_unit', (units.data || []).map((u) => [u.code, `${u.code} · ${u.name}`]), locked)}
              {text('pack_size_kg', 'md_pack', { inputMode: 'decimal' })}
              {text('standard_purchase_price', 'md_purchasePrice', { inputMode: 'decimal' })}
              {text('sale_price', 'md_salePrice', { inputMode: 'decimal' })}
              {text('shelf_life_days', 'md_shelf', { type: 'number', min: 0, max: 3650 })}
              {text('species', 'md_species', { maxLength: 20 })}
            </>}
            {PARTNER_TYPES[kind] && <>
              {text('contact_person', 'md_contact', { maxLength: 100 })}{text('phone', 'md_phone', { type: 'tel', maxLength: 30 })}
              {text('email', 'md_email', { type: 'email', maxLength: 150 })}{text('address', 'md_address', { maxLength: 255 })}
            </>}
            {kind === 'recipes' && <>
              {text('species', 'md_species', { required: true, maxLength: 20 })}
              {select('default_allocation', 'md_key', [['VALUE', t('methodValue')], ['QUANTITY', t('methodQty')]])}
            </>}
          </div>
          {kind === 'products' && update && <label className="master-checkbox"><input type="checkbox" checked={f.is_active} onChange={(e) => set('is_active', e.target.checked)} />{t('md_active')}</label>}
          {kind === 'units' && <label className="master-checkbox"><input type="checkbox" checked={f.is_weight} onChange={(e) => set('is_weight', e.target.checked)} />{t('md_weight')}</label>}
          {kind === 'recipes' && <>
            <div className="table-scroll"><table className="table entry-lines">
              <thead><tr><th>{t('md_type')}</th><th>{t('product')}</th><th>{t('md_yield')}</th><th>{t('md_valuePrice')}</th><th /></tr></thead>
              <tbody>{f.lines.map((line, i) => <tr key={i}>
                <td><select aria-label={t('md_type')} value={line.output_kind} onChange={(e) => { set('lines', f.lines.map((l, j) => i === j ? { ...l, output_kind: e.target.value, sku: '' } : l)); }}>
                  {['CUT', 'BY_PRODUCT', 'WASTE'].map((v) => <option key={v} value={v}>{t(`md_${v}`)}</option>)}
                </select></td>
                <td><Field label={t('product')} error={fe[`lines[${i}].sku`]}><select value={line.sku} required onChange={(e) => lineSet(i, 'sku', e.target.value)}>
                  <option value="">–</option>{(products.data || []).filter((p) => p.product_type === line.output_kind && p.uom_code === 'KG' && p.is_active).map((p) => <option key={p.sku} value={p.sku}>{p.sku} · {p.name}</option>)}
                </select></Field></td>
                <td><Field label={t('md_yield')} error={fe[`lines[${i}].expected_yield_pct`]}><input inputMode="decimal" pattern="[0-9]+([.,][0-9]+)?" required value={line.expected_yield_pct} onChange={(e) => lineSet(i, 'expected_yield_pct', e.target.value)} /></Field></td>
                <td>{line.output_kind !== 'WASTE' && <Field label={t('md_valuePrice')} error={fe[`lines[${i}].value_price_per_kg`]}><input inputMode="decimal" pattern="[0-9]+([.,][0-9]+)?" required value={line.value_price_per_kg ?? ''} onChange={(e) => lineSet(i, 'value_price_per_kg', e.target.value)} /></Field>}</td>
                <td><button type="button" className="btn-text" disabled={f.lines.length === 1} aria-label={t('en_removeLine')} onClick={() => set('lines', f.lines.filter((_, j) => j !== i))}>×</button></td>
              </tr>)}</tbody>
            </table></div>
            <button type="button" className="btn-text entry-pad" disabled={f.lines.length >= 30} onClick={() => set('lines', [...f.lines, newLine()])}>{t('en_addLine')}</button>
            {fe.lines && <p className="field-error entry-pad">{fe.lines}</p>}
          </>}
        </fieldset>
        {dependencyError && <ErrorState error={dependencyError} />}
        {error && <ErrorState error={error} />}
        <SubmitBar pin={pin} setPin={setPin} busy={busy} onCancel={onCancel} actions={<button type="submit" className="btn-primary" disabled={busy || pin.length < 4 || !!dependencyError || (kind === 'products' && !units.data) || (kind === 'recipes' && !products.data)}>{t('md_save')}</button>} />
      </form>
    </Group>
  );
}
