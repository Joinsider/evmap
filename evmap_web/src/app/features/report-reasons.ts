import { ReportReason } from '../core/api/models';

export const REPORT_REASONS: readonly ReportReason[] = ['spam', 'offensive', 'wrong', 'other'];

export function reportReasonLabel(reason: ReportReason): string {
  switch (reason) {
    case 'spam':
      return $localize`:@@report.reason.spam:Spam oder Werbung`;
    case 'offensive':
      return $localize`:@@report.reason.offensive:Beleidigend oder unangemessen`;
    case 'wrong':
      return $localize`:@@report.reason.wrong:Falsche Angaben`;
    case 'other':
      return $localize`:@@report.reason.other:Sonstiges`;
  }
}
