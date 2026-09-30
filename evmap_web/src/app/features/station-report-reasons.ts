import { StationReportReason } from '../core/api/models';

export const STATION_REPORT_REASONS: readonly StationReportReason[] = ['gone', 'wrong_power', 'wrong_connector', 'defective', 'other'];

export function stationReportReasonLabel(reason: StationReportReason): string {
  switch (reason) {
    case 'gone':
      return $localize`:@@stationReport.reason.gone:Station existiert nicht mehr`;
    case 'wrong_power':
      return $localize`:@@stationReport.reason.wrongPower:Falsche Leistung`;
    case 'wrong_connector':
      return $localize`:@@stationReport.reason.wrongConnector:Falscher Steckertyp`;
    case 'defective':
      return $localize`:@@stationReport.reason.defective:Defekt`;
    case 'other':
      return $localize`:@@stationReport.reason.other:Sonstiges`;
  }
}
