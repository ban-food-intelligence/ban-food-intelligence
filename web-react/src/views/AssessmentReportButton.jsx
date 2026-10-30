import React, { useState } from 'react';
import { apiFetch } from '../lib/api';
import { useLang } from '../lib/i18n';
import { ErrorState } from '../lib/ui';

export default function AssessmentReportButton() {
  const context = useLang();
  const { t } = context;
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const download = async () => {
    setBusy(true); setError(null);
    try {
      const [{ createAssessmentPdf }, report] = await Promise.all([
        import('../lib/assessmentPdf'), apiFetch('/reports/assessment'),
      ]);
      const pdf = await createAssessmentPdf(report, context);
      pdf.save(`BAN-assessment-${report.trace.root_lot_number}-${report.generated_at.slice(0, 10)}.pdf`);
    } catch (err) {
      if (err.message === 'FONT_LOAD_FAILED') err = Object.assign(new Error(t('report_missingFont')), { details: t('report_missingFont') });
      setError(err);
    } finally { setBusy(false); }
  };
  return <div className="report-action no-print">
    <button className="btn-primary" onClick={download} disabled={busy}>{t(busy ? 'report_busy' : 'report_download')}</button>
    <p className="muted">{t('report_note')}</p>
    {error && <ErrorState error={error} />}
  </div>;
}
