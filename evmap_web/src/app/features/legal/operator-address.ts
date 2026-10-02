import { Component, computed, input } from '@angular/core';
import { SiteOperator } from '../../core/api/models';
import { Loaded } from '../../core/loaded';

/**
 * Name, postal address and contact of the operator, as both legal pages show them (ADR 0024). The details come from
 * the deployment; a deployment without them says so instead of showing an empty block.
 */
@Component({
  selector: 'app-operator-address',
  template: `
    @switch (operator().state) {
      @case ('ready') {
        @if (details(); as op) {
          <address>
            {{ op.name }}<br />
            {{ op.street }}<br />
            {{ op.city }}<br />
            {{ op.country }}
          </address>
          <p class="contact">
            <span i18n="@@legal.email">E-Mail:</span>&ngsp;<a [href]="'mailto:' + op.email">{{ op.email }}</a>
            @if (op.phone) {
              <br /><span i18n="@@legal.phone">Telefon:</span>&ngsp;<a [href]="'tel:' + op.phone.replaceAll(' ', '')">{{ op.phone }}</a>
            }
          </p>
        }
      }
      @case ('failed') {
        <p class="error" role="alert" i18n="@@legal.operatorMissing">Die Angaben zum Betreiber konnten nicht geladen werden.</p>
      }
      @default {
        <p i18n="@@common.loading">Wird geladen …</p>
      }
    }
  `,
  styles: `
    address {
      font-style: normal;
    }
  `,
})
export class OperatorAddress {
  readonly operator = input.required<Loaded<SiteOperator>>();

  protected readonly details = computed(() => {
    const loaded = this.operator();
    return loaded.state === 'ready' ? loaded.value : null;
  });
}
