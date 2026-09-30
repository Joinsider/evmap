import { ReportReason, StationReportReason, SyncRunStatus } from '../core/api/models';
import { STATION_REPORT_REASONS, stationReportReasonLabel } from './station-report-reasons';
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

  it('names every station report reason', () => {
    const reasons: StationReportReason[] = ['gone', 'wrong_power', 'wrong_connector', 'defective', 'other'];
    expect([...STATION_REPORT_REASONS]).toEqual(reasons);
    expect(reasons.map(stationReportReasonLabel)).toEqual([
      'Station existiert nicht mehr',
      'Falsche Leistung',
      'Falscher Steckertyp',
      'Defekt',
      'Sonstiges',
    ]);
  });
});
