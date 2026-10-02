import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { load } from '../../core/loaded';
import { OperatorAddress } from './operator-address';

/**
 * The privacy policy for the website and the iOS app (ADR 0024). It is the readable form of
 * `docs/privacy/data-processing.md` and changes with it: a new kind of personal data, a new third party or a new
 * retention period goes into both in the same change.
 */
@Component({
  selector: 'app-privacy',
  imports: [OperatorAddress, RouterLink],
  templateUrl: './privacy.page.html',
  styleUrl: './legal.scss',
})
export class PrivacyPage {
  protected readonly operator = load(inject(EvmapApi).siteOperator());
}
