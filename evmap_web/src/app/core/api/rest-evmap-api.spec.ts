import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { firstValueFrom } from 'rxjs';
import { RestEvmapApi } from './rest-evmap-api';

describe('RestEvmapApi', () => {
  let api: RestEvmapApi;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [RestEvmapApi, provideHttpClient(), provideHttpClientTesting()] });
    api = TestBed.inject(RestEvmapApi);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('posts the code and verifier; the session comes back as a cookie, not in the body', async () => {
    const done = firstValueFrom(api.exchangeCode('github', 'the-code', 'the-verifier'), { defaultValue: undefined });
    const request = backend.expectOne('/api/v1/auth/github/code');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ code: 'the-code', codeVerifier: 'the-verifier' });
    request.flush(null, { status: 204, statusText: 'No Content' });
    await done;
  });

  it('signs out through the backend', () => {
    api.signOut().subscribe();

    expect(backend.expectOne('/api/v1/auth/logout').request.method).toBe('POST');
  });

  it('reads providers, account and the admin endpoints from same-origin paths', () => {
    api.signInProviders().subscribe();
    api.me().subscribe();
    api.adminOverview().subscribe();
    api.adminSyncRuns(20).subscribe();

    const providers = backend.expectOne('/api/v1/auth/providers');
    expect(providers.request.method).toBe('GET');
    providers.flush([]);
    backend.expectOne('/api/v1/me').flush({});
    backend.expectOne('/api/v1/admin/overview').flush({});
    backend.expectOne((request) => request.url === '/api/v1/admin/sync-runs' && request.params.get('limit') === '20').flush([]);
  });

  it('deletes the account, reads the data export as a blob and lifts blocks by their own id', () => {
    api.deleteAccount().subscribe();
    api.exportData().subscribe();
    api.contributions().subscribe();
    api.blocks().subscribe();
    api.unblock('b/1').subscribe();

    expect(backend.expectOne((request) => request.method === 'DELETE' && request.url === '/api/v1/me').request.method).toBe('DELETE');
    const download = backend.expectOne('/api/v1/me/export');
    expect(download.request.responseType).toBe('blob');
    download.flush(new Blob(['{}']));
    backend.expectOne('/api/v1/me/contributions').flush({ comments: [], reports: [], stationReports: [] });
    backend.expectOne('/api/v1/me/blocks').flush([]);
    expect(backend.expectOne('/api/v1/me/blocks/b%2F1').request.method).toBe('DELETE');
  });

  it('reads the site operator from the web container and drives the moderation queue', () => {
    api.siteOperator().subscribe();
    api.adminReports().subscribe();
    api.adminDismissReports('c-1').subscribe();
    api.adminRemoveComment('c-2').subscribe();

    backend.expectOne('/site-operator.json').flush({});
    backend.expectOne('/api/v1/admin/reports').flush([]);
    expect(backend.expectOne('/api/v1/admin/reports/c-1/dismiss').request.method).toBe('POST');
    expect(backend.expectOne('/api/v1/admin/comments/c-2').request.method).toBe('DELETE');
  });

  it('drives the station report queue', () => {
    api.adminStationReports().subscribe();
    api.adminCloseStationReports('s/1', 'wrong_power', 'resolve').subscribe();
    api.adminCloseStationReports('s-2', 'gone', 'dismiss').subscribe();

    backend.expectOne('/api/v1/admin/station-reports').flush([]);
    expect(backend.expectOne('/api/v1/admin/station-reports/s%2F1/wrong_power/resolve').request.method).toBe('POST');
    expect(backend.expectOne('/api/v1/admin/station-reports/s-2/gone/dismiss').request.method).toBe('POST');
  });

  it('sends the map query with repeated, sorted filter params and whole kilometres', () => {
    api
      .stations({
        latitude: 48.77,
        longitude: 9.18,
        radiusKm: 12.2,
        limit: 600,
        connectorTypes: ['Type 2', 'CCS'],
        minPowerKw: 100,
        excludeOperators: ['Zeta', 'Alpha'],
      })
      .subscribe();

    const request = backend.expectOne((r) => r.url === '/api/v1/stations');
    expect(request.request.params.get('latitude')).toBe('48.77');
    expect(request.request.params.get('radiusKm')).toBe('13');
    expect(request.request.params.get('limit')).toBe('600');
    expect(request.request.params.getAll('connectorType')).toEqual(['CCS', 'Type 2']);
    expect(request.request.params.get('minPowerKw')).toBe('100');
    expect(request.request.params.getAll('excludeOperator')).toEqual(['Alpha', 'Zeta']);
    expect(request.request.params.has('includeOperator')).toBe(false);
    request.flush([]);
  });

  it('sends an allowlist only when there is one', () => {
    api.stations({ latitude: 0, longitude: 0, radiusKm: 1, limit: 10, connectorTypes: [], excludeOperators: [], includeOperators: ['EnBW'] }).subscribe();

    const request = backend.expectOne((r) => r.url === '/api/v1/stations');
    expect(request.request.params.getAll('includeOperator')).toEqual(['EnBW']);
    expect(request.request.params.has('minPowerKw')).toBe(false);
    request.flush([]);
  });

  it('reads a station, its live state, prices, comments, the live viewport, the directory and the map token', () => {
    api.station('s/1').subscribe();
    api.stationAvailability('s1').subscribe();
    api.chargePoints('s1').subscribe();
    api.comments('s1').subscribe();
    api.availabilityInBounds({ latMin: 48, lonMin: 9, latMax: 49, lonMax: 10 }).subscribe();
    api.operators(' enbw ', 50).subscribe();
    api.operators('', 50).subscribe();
    api.mapToken().subscribe();

    backend.expectOne('/api/v1/stations/s%2F1').flush({});
    backend.expectOne('/api/v1/stations/s1/availability').flush({});
    backend.expectOne('/api/v1/stations/s1/charge-points').flush({});
    backend.expectOne('/api/v1/stations/s1/comments').flush([]);
    const live = backend.expectOne((r) => r.url === '/api/v1/stations/availability');
    expect(live.request.params.get('latMax')).toBe('49');
    live.flush([]);
    const directory = backend.match((r) => r.url === '/api/v1/operators');
    expect(directory.map((r) => r.request.params.get('query'))).toEqual(['enbw', null]);
    directory.forEach((r) => r.flush([]));
    backend.expectOne('/api/v1/map/token').flush({ token: 't', expiresAt: '2026-10-02T12:30:00Z' });
  });
});
