import { DatePipe } from '@angular/common';
import { Component, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { BlockedAuthor, Contributions } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { LegalService } from '../../core/legal.service';
import { load } from '../../core/loaded';
import { FILE_SAVER } from '../../core/save-file';
import { providerName } from '../login/provider-labels';
import { reportReasonLabel } from '../report-reasons';
import { stationReportReasonLabel } from '../station-report-reasons';

type DeletionStage = 'idle' | 'confirm' | 'deleting' | 'failed';

/**
 * The account area (ADR 0020): linked sign-ins, what the person contributed, blocked authors, the data
 * export and the deletion App Store review and the GDPR both require. Deleting is immediate and
 * complete, so it takes a second, explicit step.
 */
@Component({
  selector: 'app-account',
  imports: [DatePipe],
  templateUrl: './account.page.html',
  styleUrl: './account.page.scss',
})
export class AccountPage {
  private readonly api = inject(EvmapApi);
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly saveFile = inject(FILE_SAVER);
  protected readonly privacyPolicyUrl = inject(LegalService).privacyPolicyUrl;

  protected readonly identities = computed(() => this.auth.account()?.identities ?? []);
  protected readonly contributions = load<Contributions>(this.api.contributions());
  protected readonly blocks = load<BlockedAuthor[]>(this.api.blocks());
  /** Blocks lifted on this page; hidden at once instead of reloading the list. */
  protected readonly lifted = signal<ReadonlySet<string>>(new Set());
  protected readonly exportFailed = signal(false);
  protected readonly deletion = signal<DeletionStage>('idle');

  protected providerName = providerName;
  protected reasonLabel = reportReasonLabel;
  protected stationReasonLabel = stationReportReasonLabel;

  /** The blocks still in force: the ones lifted on this page are gone from the list at once. */
  protected remaining(blocks: BlockedAuthor[]) {
    return blocks.filter((block) => !this.lifted().has(block.id));
  }

  protected async unblock(block: BlockedAuthor) {
    await firstValueFrom(this.api.unblock(block.id), { defaultValue: undefined });
    this.lifted.update((ids) => new Set(ids).add(block.id));
  }

  protected async download() {
    this.exportFailed.set(false);
    try {
      this.saveFile(await firstValueFrom(this.api.exportData()), 'evmap-export.json');
    } catch {
      this.exportFailed.set(true);
    }
  }

  protected askToDelete() {
    this.deletion.set('confirm');
  }

  protected cancelDeletion() {
    this.deletion.set('idle');
  }

  protected async confirmDeletion() {
    this.deletion.set('deleting');
    try {
      await this.auth.deleteAccount();
    } catch {
      this.deletion.set('failed');
      return;
    }
    await this.router.navigateByUrl('/');
  }
}
