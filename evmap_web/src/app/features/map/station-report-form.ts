import { Component, computed, inject, input, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { StationReportReason } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { STATION_REPORT_REASONS, stationReportReasonLabel } from '../station-report-reasons';

/** The backend's limit on the free text (ADR 0021). */
export const MAX_NOTE = 500;

/**
 * "Fehler melden" at a station (ADR 0021): a closed reason plus an optional note, signed in only. The report lands in
 * the admin queue; master data changes only with the next sync, which the form says.
 */
@Component({
  selector: 'app-station-report-form',
  imports: [RouterLink],
  template: `
    @switch (state()) {
      @case ('closed') {
        @if (auth.signedIn()) {
          <button type="button" class="link" (click)="open()" i18n="@@station.report">Fehler melden</button>
        } @else {
          <a routerLink="/login" [queryParams]="{ returnUrl: returnUrl() }" i18n="@@station.report.loginRequired">Zum Melden eines Fehlers bitte anmelden.</a>
        }
      }
      @case ('sent') {
        <p role="status"><strong i18n="@@station.report.thanks.title">Danke!</strong> <ng-container i18n="@@station.report.thanks.message">Deine Meldung ist eingegangen und wird geprüft.</ng-container></p>
      }
      @default {
        <form (submit)="$event.preventDefault(); send()">
          <fieldset>
            <legend i18n="@@station.report.reason">Was stimmt nicht?</legend>
            @for (option of reasons; track option) {
              <label class="check">
                <input type="radio" name="reason" [value]="option" [checked]="reason() === option" (change)="reason.set(option)" />
                {{ label(option) }}
              </label>
            }
          </fieldset>
          <label class="note">
            <span i18n="@@station.report.note">Anmerkung (optional)</span>
            <textarea rows="3" [maxLength]="maxNote" [value]="note()" (input)="note.set($any($event.target).value)"></textarea>
          </label>
          <p class="muted" i18n="@@station.report.footer">Bitte keine persönlichen Daten angeben. Wir prüfen die Meldung; die Stationsdaten ändern sich nicht sofort.</p>
          @if (state() === 'failed') {
            <p class="error" role="alert" i18n="@@station.report.failed">Die Meldung konnte nicht gesendet werden. Bitte versuche es erneut.</p>
          }
          <div class="actions">
            <button type="submit" class="primary" [disabled]="!reason() || state() === 'sending'" i18n="@@station.report.send">Senden</button>
            <button type="button" class="link" [disabled]="state() === 'sending'" (click)="state.set('closed')" i18n="@@action.cancel">Abbrechen</button>
          </div>
        </form>
      }
    }
  `,
  styleUrl: './station-report-form.scss',
})
export class StationReportForm {
  private readonly api = inject(EvmapApi);
  protected readonly auth = inject(AuthService);

  readonly stationId = input.required<string>();

  protected readonly reasons = STATION_REPORT_REASONS;
  protected readonly label = stationReportReasonLabel;
  protected readonly maxNote = MAX_NOTE;
  protected readonly state = signal<'closed' | 'open' | 'sending' | 'failed' | 'sent'>('closed');
  protected readonly reason = signal<StationReportReason | null>(null);
  protected readonly note = signal('');
  protected readonly returnUrl = computed(() => `/station/${this.stationId()}`);

  protected open() {
    this.reason.set(null);
    this.note.set('');
    this.state.set('open');
  }

  protected async send() {
    const reason = this.reason();
    if (!reason) return;
    this.state.set('sending');
    try {
      const note = this.note().trim();
      await firstValueFrom(this.api.reportStation(this.stationId(), reason, note || undefined), { defaultValue: undefined });
      this.state.set('sent');
    } catch {
      this.state.set('failed');
    }
  }
}
