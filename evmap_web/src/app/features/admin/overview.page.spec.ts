import { TestBed } from '@angular/core/testing';
import { EvmapApi } from '../../core/api/evmap-api';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { OverviewPage } from './overview.page';

describe('OverviewPage', () => {
  it('shows the counts and the sync runs with their status', async () => {
    const api = new FakeEvmapApi();
    api.overview = { stations: 151234, chargePoints: 402311, accounts: 17, comments: 5, openReports: 3 };
    api.runs = [
      { id: 'r1', startedAt: '2026-09-29T03:00:00Z', finishedAt: '2026-09-29T03:40:00Z', status: 'PARTIAL', processed: 10, created: 1,
        updated: 2, unchanged: 7, failed: 1, errorMessage: 'OCM: timeout' },
    ];
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });

    const fixture = TestBed.createComponent(OverviewPage);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent!;

    expect(text).toContain('151.234');
    expect(text).toContain('Offene Meldungen');
    expect(text).toContain('Unvollständig');
    expect(text).toContain('OCM: timeout');
    expect((fixture.nativeElement as HTMLElement).querySelector('.status.partial')).not.toBeNull();
  });
});
