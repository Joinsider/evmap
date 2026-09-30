import { TestBed } from '@angular/core/testing';
import { EvmapApi } from '../../core/api/evmap-api';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { ReportsPage } from './reports.page';

describe('ReportsPage', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    api = new FakeEvmapApi();
    api.reported = [
      { commentId: 'c1', stationId: 's1', stationName: 'EnBW Stuttgart', body: 'Kaufe billig Strom', commentCreatedAt: '2026-09-01T10:00:00Z',
        lastReportedAt: '2026-09-02T10:00:00Z', reasons: { spam: 2, other: 1 } },
      { commentId: 'c2', stationId: 's2', stationName: 'Ionity', body: 'Defekt', commentCreatedAt: '2026-09-01T11:00:00Z',
        lastReportedAt: '2026-09-02T11:00:00Z', reasons: { wrong: 1 } },
    ];
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
  });

  async function open() {
    const fixture = TestBed.createComponent(ReportsPage);
    await fixture.whenStable();
    return fixture;
  }

  const button = (root: HTMLElement, label: string) => Array.from(root.querySelectorAll('button')).find((b) => b.textContent!.includes(label))!;

  it('shows each reported comment with its reasons and counts, but no reporter', async () => {
    const root = (await open()).nativeElement as HTMLElement;

    expect(root.textContent).toContain('Kaufe billig Strom');
    expect(root.textContent).toContain('Spam oder Werbung × 2');
    expect(root.textContent).toContain('Sonstiges × 1');
    expect(root.textContent).toContain('Falsche Angaben × 1');
    expect(root.querySelectorAll('article').length).toBe(2);
  });

  it('dismisses reports in one click and drops the entry', async () => {
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;

    button(root, 'Meldungen abweisen').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.calls).toEqual(['dismiss:c1']);
    expect(root.querySelectorAll('article').length).toBe(1);
  });

  it('removes a comment only after asking a second time', async () => {
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;

    button(root, 'Kommentar löschen').click();
    fixture.detectChanges();
    expect(api.calls).toEqual([]);

    button(root, 'Endgültig löschen').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.calls).toEqual(['remove:c1']);
    expect(root.querySelectorAll('article').length).toBe(1);
  });

  it('says when nothing is left to decide', async () => {
    api.reported = [];
    const root = (await open()).nativeElement as HTMLElement;

    expect(root.textContent).toContain('Keine offenen Meldungen.');
  });
});
