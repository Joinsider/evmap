import { Component, LOCALE_ID, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { NgTemplateOutlet } from '@angular/common';
import { RouterLink } from '@angular/router';
import { Observable, firstValueFrom } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { CommentPayload, ReportReason, StationComment } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { REPORT_REASONS, reportReasonLabel } from '../report-reasons';
import { formatDate } from './domain/format';
import { formatAmount } from './domain/prices';
import { MAX_BODY, MAX_EXPERIENCE, commentPayload, priceInput } from './domain/comment-form';

/** What the section is doing besides listing: writing, editing one comment, confirming a delete, or moderating one. */
type Mode = { kind: 'list' } | { kind: 'write' } | { kind: 'edit'; id: string } | { kind: 'delete'; id: string } | { kind: 'moderate'; id: string };

/**
 * The comments of one station (ADR 0023, phase 8b): everyone reads them; signed in, people write, edit and delete their
 * own, and report other people's or block their author (ADR 0020) — the web counterpart of the iOS
 * `CommentListSection`. After every change the list is loaded again, because the backend decides what this viewer sees
 * (a reported comment or a blocked author is hidden for them alone).
 */
@Component({
  selector: 'app-comments-section',
  imports: [RouterLink, NgTemplateOutlet],
  templateUrl: './comments-section.html',
  styleUrl: './comments-section.scss',
})
export class CommentsSection {
  private readonly api = inject(EvmapApi);
  protected readonly auth = inject(AuthService);
  private readonly locale = inject(LOCALE_ID);

  readonly stationId = input.required<string>();

  protected readonly maxBody = MAX_BODY;
  protected readonly maxExperience = MAX_EXPERIENCE;
  protected readonly reasons = REPORT_REASONS;
  protected readonly reasonLabel = reportReasonLabel;

  protected readonly comments = rxResource({
    // The viewer is part of the request: signing in or out changes which comments are theirs and which are hidden.
    params: () => ({ station: this.stationId(), viewer: this.auth.account()?.id ?? null }),
    stream: ({ params }) => this.api.comments(params.station),
  });

  protected readonly mode = signal<Mode>({ kind: 'list' });
  protected readonly busy = signal(false);
  protected readonly failed = signal(false);
  /** The form's fields as typed; the price stays text until it is sent. */
  protected readonly body = signal('');
  protected readonly experience = signal('');
  protected readonly price = signal('');

  protected readonly payload = computed(() => commentPayload(this.body(), this.experience(), this.price()));
  protected readonly loginLink = computed(() => `/station/${this.stationId()}`);

  protected meta(comment: StationComment) {
    const parts = [formatDate(comment.createdAt, this.locale)];
    if (comment.paidPriceCents !== undefined && comment.paidPriceCents !== null) {
      const amount = formatAmount(comment.paidPriceCents / 100, 'EUR', this.locale);
      parts.push($localize`:@@comments.paid:Bezahlt: ${amount}:amount:`);
    }
    if (comment.experience) parts.push(comment.experience);
    return parts.join(' · ');
  }

  protected is(kind: Mode['kind'], comment?: StationComment) {
    const mode = this.mode();
    return mode.kind === kind && (!comment || ('id' in mode && mode.id === comment.id));
  }

  protected write() {
    this.fill('', '', '');
    this.show({ kind: 'write' });
  }

  protected edit(comment: StationComment) {
    this.fill(comment.body, comment.experience ?? '', priceInput(comment.paidPriceCents, this.locale));
    this.show({ kind: 'edit', id: comment.id });
  }

  protected confirmDelete(comment: StationComment) {
    this.show({ kind: 'delete', id: comment.id });
  }

  protected moderate(comment: StationComment) {
    this.show({ kind: 'moderate', id: comment.id });
  }

  protected cancel() {
    this.show({ kind: 'list' });
  }

  protected save() {
    const payload = this.payload();
    const mode = this.mode();
    if (!payload) return;
    void this.run(
      mode.kind === 'edit' ? this.api.updateComment(mode.id, payload) : this.api.createComment(this.stationId(), payload),
    );
  }

  protected delete(comment: StationComment) {
    void this.run(this.api.deleteComment(comment.id));
  }

  protected report(comment: StationComment, reason: ReportReason) {
    void this.run(this.api.reportComment(comment.id, reason));
  }

  protected block(comment: StationComment) {
    void this.run(this.api.blockAuthor(comment.id));
  }

  protected setBody(value: string) {
    this.body.set(value);
  }

  protected setExperience(value: string) {
    this.experience.set(value);
  }

  protected setPrice(value: string) {
    this.price.set(value);
  }

  private fill(body: string, experience: string, price: string) {
    this.body.set(body);
    this.experience.set(experience);
    this.price.set(price);
  }

  private show(mode: Mode) {
    this.failed.set(false);
    this.mode.set(mode);
  }

  /** Sends one change; on success the form closes and the list reloads, on failure the form stays with its text. */
  private async run(request: Observable<unknown>) {
    this.busy.set(true);
    this.failed.set(false);
    try {
      await firstValueFrom(request, { defaultValue: undefined });
      this.mode.set({ kind: 'list' });
      this.comments.reload();
    } catch {
      this.failed.set(true);
    } finally {
      this.busy.set(false);
    }
  }

  /** For the form's template: the payload type, so a blank comment cannot be saved. */
  protected canSave(payload: CommentPayload | null) {
    return payload !== null && !this.busy();
  }
}
