import { TestBed } from '@angular/core/testing';
import { EvmapApi } from '../../core/api/evmap-api';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { StationReportsPage } from './station-reports.page';

describe('StationReportsPage', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    api = new FakeEvmapApi();
    api.stationReports = [
      { stationId: 's1', stationName: 'EnBW Stuttgart', operatorName: 'EnBW', city: 'Stuttgart', reason: 'wrong_power', count: 2,
        lastReportedAt: '2026-09-02T10:00:00Z', notes: ['Es sind nur 11 kW', 'Leistung stimmt nicht'] },
      { stationId: 's2', stationName: 'Ionity', reason: 'gone', count: 1, lastReportedAt: '2026-09-02T11:00:00Z', notes: [] },
    ];
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
  });

  async function open() {
    const fixture = TestBed.createComponent(StationReportsPage);
    await fixture.whenStable();
    return fixture;
  }

  const button = (root: HTMLElement, label: string) => Array.from(root.querySelectorAll('button')).find((b) => b.textContent!.includes(label))!;

  it('shows each line with its reason, count and the reporters\' notes, but no reporter', async () => {
    const root = (await open()).nativeElement as HTMLElement;

    expect(root.textContent).toContain('EnBW Stuttgart');
    expect(root.textContent).toContain('Falsche Leistung × 2');
    expect(root.textContent).toContain('Es sind nur 11 kW');
    expect(root.textContent).toContain('Station existiert nicht mehr × 1');
    expect(root.querySelectorAll('article').length).toBe(2);
  });

  it('resolves a line in one click and drops it', async () => {
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;

    button(root, 'Erledigt').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.calls).toEqual(['resolve:s1:wrong_power']);
    expect(root.querySelectorAll('article').length).toBe(1);
  });

  it('dismisses a line in one click and drops it', async () => {
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;

    button(root, 'Abweisen').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.calls).toEqual(['dismiss:s1:wrong_power']);
    expect(root.querySelectorAll('article').length).toBe(1);
  });

  it('says when nothing is left to decide', async () => {
    api.stationReports = [];
    const root = (await open()).nativeElement as HTMLElement;

    expect(root.textContent).toContain('Keine offenen Meldungen.');
  });
});
