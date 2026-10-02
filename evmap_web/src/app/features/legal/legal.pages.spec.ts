import { Type } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { ImprintPage } from './imprint.page';
import { PrivacyPage } from './privacy.page';

describe('legal pages', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    api = new FakeEvmapApi();
  });

  async function render(page: Type<unknown>): Promise<HTMLElement> {
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }, provideRouter([])] });
    const fixture = TestBed.createComponent(page);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  for (const [name, page] of [['Impressum', ImprintPage], ['privacy policy', PrivacyPage]] as const) {
    it(`shows the operator from the deployment on the ${name}`, async () => {
      api.operatorData = { ...api.operatorData!, phone: '+49 221 123 45' };
      const root = await render(page);

      const address = root.querySelector('address')!.textContent!;
      expect(address).toContain('Erika Mustermann');
      expect(address).toContain('Heidestraße 17');
      expect(address).toContain('51147 Köln');
      expect(root.querySelector('a[href="mailto:kontakt@evmap.example"]')).not.toBeNull();
      expect(root.querySelector('a[href="tel:+4922112345"]')).not.toBeNull();
    });

    it(`says so on the ${name} when the deployment has no operator details`, async () => {
      api.operatorData = null;
      const root = await render(page);

      expect(root.querySelector('address')).toBeNull();
      expect(root.querySelector('[role="alert"]')!.textContent).toContain('Betreiber');
    });
  }

  it('names every third party the policy relies on', async () => {
    const text = (await render(PrivacyPage)).textContent!;

    for (const party of ['Apple', 'Google', 'GitHub', 'MobiData BW', 'transport.data.gouv.fr', 'evmap_session', 'XSRF-TOKEN', 'evmap.stationSettings.v1']) {
      expect(text).toContain(party);
    }
  });
});
