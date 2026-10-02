import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { load } from '../../core/loaded';
import { OperatorAddress } from './operator-address';

/**
 * The Impressum (§ 5 DDG) of a private, non-commercial operator (ADR 0024). Name and address come from the
 * deployment; the data-source licences are listed here as well, because CC BY and dl-de/by ask for a named source.
 */
@Component({
  selector: 'app-imprint',
  imports: [OperatorAddress, RouterLink],
  templateUrl: './imprint.page.html',
  styleUrl: './legal.scss',
})
export class ImprintPage {
  protected readonly operator = load(inject(EvmapApi).siteOperator());
}
