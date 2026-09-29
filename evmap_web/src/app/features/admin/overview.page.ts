import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { EvmapApi } from '../../core/api/evmap-api';
import { AdminOverview, SyncRun } from '../../core/api/models';
import { load } from '../../core/loaded';
import { syncStatusLabel } from './sync-status';

/** Read-only ingestion health and a few counts — the admin area's phase 1 content (ADR 0018). */
@Component({
  selector: 'app-admin-overview',
  imports: [DatePipe, DecimalPipe],
  templateUrl: './overview.page.html',
  styleUrl: './overview.page.scss',
})
export class OverviewPage {
  private readonly api = inject(EvmapApi);

  protected readonly overview = load<AdminOverview>(this.api.adminOverview());
  protected readonly runs = load<SyncRun[]>(this.api.adminSyncRuns(20));

  protected statusLabel = syncStatusLabel;
}
