import React, { useState } from 'react';
import { useApi } from '../lib/api';
import { useLang } from '../lib/i18n';
import { Async, Group, Mono, Page, Pill, Table } from '../lib/ui';

export default function AI() {
  const { t } = useLang();
  const result = useApi('/ai/summary');
  return (
    <Page title={t('ai_title')} subtitle={t('ai_sub')}>
      <Async result={result}>
        {(s) => (
          <>
            <Group title={t('chartTitle')} footer={t('aiNote')}>
              <MapeChart metrics={s.metrics} baseline={s.baseline} />
            </Group>
            <MetricsTable metrics={s.metrics} />
            <Comparisons rows={s.comparisons} />
          </>
        )}
      </Async>
    </Page>
  );
}

/** Horizontal bars: model vs baseline MAPE per target (2 validated hues, legend + direct labels + hover). */
function MapeChart({ metrics, baseline }) {
  const { t, fmt } = useLang();
  const [hover, setHover] = useState(null);
  const targets = [];
  metrics.forEach((m) => {
    const k = `${m.task}|${m.target}`;
    if (!targets.find((x) => x.key === k)) targets.push({ key: k, task: m.task, target: m.target });
  });
  const rows = targets.map((tg) => ({
    ...tg,
    model: metrics.find((m) => `${m.task}|${m.target}` === tg.key && m.role === 'MODEL'),
    base: metrics.find((m) => `${m.task}|${m.target}` === tg.key && m.model === baseline),
  }));
  const max = Math.max(...rows.flatMap((r) => [r.model?.mape_pct || 0, r.base?.mape_pct || 0]), 1);
  const labelW = 170;
  const barH = 14;
  const rowH = 52;
  const width = 640;
  const plotW = width - labelW - 70;
  const height = rows.length * rowH + 8;

  return (
    <div className="chart">
      <div className="legend" aria-hidden="true">
        <span><i className="swatch s1" />{t('model')}</span>
        <span><i className="swatch s2" />{t('baseline')}</span>
      </div>
      <svg viewBox={`0 0 ${width} ${height}`} role="img" aria-label={t('chartTitle')} className="chart-svg">
        {rows.map((r, i) => {
          const y = i * rowH + 6;
          const bars = [
            { k: 'model', v: r.model?.mape_pct, cls: 's1', dy: 0, name: t('model') },
            { k: 'base', v: r.base?.mape_pct, cls: 's2', dy: barH + 3, name: t('baseline') },
          ];
          return (
            <g key={r.key}>
              <text x={0} y={y + barH + 2} className="chart-label">{t(`t_${r.target}`)}</text>
              <text x={0} y={y + barH * 2 + 6} className="chart-sublabel">{t(`task_${r.task}`)}</text>
              {bars.map((b) => {
                const w = Math.max(2, ((b.v || 0) / max) * plotW);
                const id = `${r.key}-${b.k}`;
                return (
                  <g key={b.k} onMouseEnter={() => setHover({ id, r, b })} onMouseLeave={() => setHover(null)}>
                    <rect x={labelW} y={y + b.dy - 4} width={plotW + 60} height={barH + 6} fill="transparent" />
                    <rect x={labelW} y={y + b.dy} width={w} height={barH} rx={4} className={`bar ${b.cls} ${hover && hover.id !== id ? 'dim' : ''}`} />
                    <text x={labelW + w + 6} y={y + b.dy + barH - 3} className="chart-value">{fmt.pct(b.v)}</text>
                  </g>
                );
              })}
            </g>
          );
        })}
      </svg>
      {hover && (
        <div className="tooltip" role="status">
          <strong>{t(`t_${hover.r.target}`)}</strong> · {hover.b.name}
          <div>{t('mape')} {fmt.pct(hover.b.v)} · {t('mae')} {fmt.num(hover.b.k === 'model' ? hover.r.model?.mae : hover.r.base?.mae, 3)} {hover.r.model?.unit}</div>
        </div>
      )}
    </div>
  );
}

function MetricsTable({ metrics }) {
  const { t, fmt } = useLang();
  return (
    <Group title={`${t('mae')} / ${t('mape')}`}>
      <Table
        rows={metrics}
        columns={[
          { key: 'task', label: '', render: (m) => t(`task_${m.task}`) },
          { key: 'target', label: '', render: (m) => t(`t_${m.target}`) },
          { key: 'model', label: t('model'), render: (m) => <Mono>{m.model}</Mono> },
          { key: 'mae', label: t('mae'), align: 'num', render: (m) => `${fmt.num(m.mae, 3)} ${m.unit}` },
          { key: 'mape', label: t('mape'), align: 'num', render: (m) => <strong className={m.role === 'MODEL' ? 'pos' : ''}>{fmt.pct(m.mape_pct)}</strong> },
          { key: 'n', label: t('rows'), align: 'num', render: (m) => fmt.num(m.n_rows, 0) },
        ]}
      />
    </Group>
  );
}

function Comparisons({ rows }) {
  const { t, fmt } = useLang();
  const show = (r, v) => (v == null ? '–' : r.target === 'ekp' ? fmt.money(v) : r.target === 'actual_qty' ? fmt.num(v, 1) : fmt.qty(v, 'KG'));
  const dev = (v) => (v == null ? '' : ` (${v > 0 ? '+' : ''}${fmt.pct(v, 1)})`);
  // interval_* and anomaly are optional in /ai/summary: the columns appear only when the API sends them
  const level = rows.find((r) => r.interval_level != null)?.interval_level;
  const hasStatus = rows.some((r) => r.anomaly != null);
  const extra = [];
  if (level != null) {
    extra.push({ key: 'interval', label: t('interval', fmt.num(level * 100, 0)), align: 'num', render: (r) => (r.interval_low == null ? '–' : `${show(r, r.interval_low)} – ${show(r, r.interval_high)}`) });
  }
  if (hasStatus) {
    extra.push({
      key: 'status', label: t('status'),
      render: (r) => (r.anomaly == null ? '' : r.anomaly ? <Pill tone="bad">{t('st_anomaly')}</Pill>
        : r.outside_interval ? <Pill tone="warn">{t('st_outside')}</Pill> : <Pill tone="ok">{t('st_inside')}</Pill>),
    });
  }
  return (
    <Group title={t('predictions')} footer={level != null ? t('intervalNote') : undefined}>
      <Table
        rows={rows}
        columns={[
          { key: 'ref_no', label: t('document'), render: (r) => <Mono>{r.ref_no}</Mono> },
          { key: 'target', label: '', render: (r) => t(`t_${r.target}`) },
          { key: 'actual', label: t('actual'), align: 'num', render: (r) => <strong>{show(r, r.actual)}</strong> },
          { key: 'model', label: t('model'), align: 'num', render: (r) => <>{show(r, r.model_pred)}<span className="cell-sub">{dev(r.model_dev_pct)}</span></> },
          { key: 'base', label: t('baseline'), align: 'num', render: (r) => <>{show(r, r.baseline_pred)}<span className="cell-sub">{dev(r.baseline_dev_pct)}</span></> },
          ...extra,
        ]}
      />
    </Group>
  );
}
