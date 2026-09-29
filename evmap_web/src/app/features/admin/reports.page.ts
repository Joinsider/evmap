import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { ReportReason, ReportedComment } from '../../core/api/models';
import { load } from '../../core/loaded';
import { REPORT_REASONS, reportReasonLabel } from '../report-reasons';

/**
 * The moderation queue (ADR 0020). Each entry is a reported comment with how often and why it was
 * reported — never by whom. The only decisions are to remove the comment or to dismiss the reports;
 * removing asks a second time because it cannot be undone.
 */
@Component({
  selector: 'app-admin-reports',
  imports: [DatePipe],
  templateUrl: './reports.page.html',
  styleUrl: './reports.page.scss',
})
export class ReportsPage {
  private readonly api = inject(EvmapApi);

  protected readonly queue = load<ReportedComment[]>(this.api.adminReports());
  /** Decided on this page; dropped from the list at once instead of reloading it. */
  protected readonly decided = signal<ReadonlySet<string>>(new Set());
  /** The comment whose removal awaits its second click. */
  protected readonly confirming = signal<string | null>(null);
  protected readonly failed = signal(false);

  protected reasonLabel = reportReasonLabel;

  protected reasonsOf(item: ReportedComment): { reason: ReportReason; count: number }[] {
    return REPORT_REASONS.filter((reason) => (item.reasons[reason] ?? 0) > 0).map((reason) => ({ reason, count: item.reasons[reason]! }));
  }

  /** The queue without what was decided on this page. */
  protected pending(items: ReportedComment[]) {
    return items.filter((item) => !this.decided().has(item.commentId));
  }

  protected askToRemove(item: ReportedComment) {
    this.confirming.set(item.commentId);
  }

  protected async remove(item: ReportedComment) {
    await this.decide(item, this.api.adminRemoveComment(item.commentId));
  }

  protected async dismiss(item: ReportedComment) {
    await this.decide(item, this.api.adminDismissReports(item.commentId));
  }

  private async decide(item: ReportedComment, request: ReturnType<EvmapApi['adminRemoveComment']>) {
    this.failed.set(false);
    try {
      await firstValueFrom(request, { defaultValue: undefined });
      this.decided.update((ids) => new Set(ids).add(item.commentId));
      this.confirming.set(null);
    } catch {
      this.failed.set(true);
    }
  }
}
