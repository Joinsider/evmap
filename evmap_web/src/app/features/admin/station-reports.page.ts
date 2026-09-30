import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { ReportedStation } from '../../core/api/models';
import { load } from '../../core/loaded';
import { stationReportReasonLabel } from '../station-report-reasons';

/**
 * The queue of station error reports (ADR 0021). One line is the open reports of one station for one
 * reason — how many and what they wrote, never by whom. "Erledigt" and "Abweisen" only close the
 * reports: master data belongs to the sync and is not edited from here.
 */
@Component({
  selector: 'app-admin-station-reports',
  imports: [DatePipe],
  templateUrl: './station-reports.page.html',
  styleUrl: './station-reports.page.scss',
})
export class StationReportsPage {
  private readonly api = inject(EvmapApi);

  protected readonly queue = load<ReportedStation[]>(this.api.adminStationReports());
  /** Closed on this page; dropped from the list at once instead of reloading it. */
  protected readonly closed = signal<ReadonlySet<string>>(new Set());
  protected readonly failed = signal(false);

  protected reasonLabel = stationReportReasonLabel;

  private static key(item: ReportedStation): string {
    return `${item.stationId}:${item.reason}`;
  }

  protected trackKey = StationReportsPage.key;

  /** The queue without what was closed on this page. */
  protected pending(items: ReportedStation[]) {
    return items.filter((item) => !this.closed().has(StationReportsPage.key(item)));
  }

  protected async resolve(item: ReportedStation) {
    await this.close(item, 'resolve');
  }

  protected async dismiss(item: ReportedStation) {
    await this.close(item, 'dismiss');
  }

  private async close(item: ReportedStation, outcome: 'resolve' | 'dismiss') {
    this.failed.set(false);
    try {
      await firstValueFrom(this.api.adminCloseStationReports(item.stationId, item.reason, outcome), { defaultValue: undefined });
      this.closed.update((keys) => new Set(keys).add(StationReportsPage.key(item)));
    } catch {
      this.failed.set(true);
    }
  }
}
