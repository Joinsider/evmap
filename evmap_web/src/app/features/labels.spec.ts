import { ReportReason, SyncRunStatus } from '../core/api/models';
import { REPORT_REASONS, reportReasonLabel } from './report-reasons';
import { syncStatusLabel } from './admin/sync-status';
import { signInLabel } from './login/provider-labels';

describe('labels', () => {
  it('names every sync status', () => {
    const statuses: SyncRunStatus[] = ['RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED', 'SKIPPED'];
    expect(statuses.map(syncStatusLabel)).toEqual(['Läuft', 'Erfolgreich', 'Unvollständig', 'Fehlgeschlagen', 'Übersprungen']);
  });

  it('names every provider button', () => {
    expect(signInLabel('apple')).toBe('Mit Apple anmelden');
    expect(signInLabel('google')).toBe('Mit Google anmelden');
    expect(signInLabel('github')).toBe('Mit GitHub anmelden');
  });

  it('names every report reason', () => {
    const reasons: ReportReason[] = ['spam', 'offensive', 'wrong', 'other'];
    expect([...REPORT_REASONS]).toEqual(reasons);
    expect(reasons.map(reportReasonLabel)).toEqual(['Spam oder Werbung', 'Beleidigend oder unangemessen', 'Falsche Angaben', 'Sonstiges']);
  });
});
