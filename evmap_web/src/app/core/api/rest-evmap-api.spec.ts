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

  it('posts the code and verifier and returns only the access token', async () => {
    const token = firstValueFrom(api.exchangeCode('github', 'the-code', 'the-verifier'));
    const request = backend.expectOne('/api/v1/auth/github/code');
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ code: 'the-code', codeVerifier: 'the-verifier' });
    request.flush({ accessToken: 'issued' });
    expect(await token).toBe('issued');
  });

  it('reads providers, account and the admin endpoints from same-origin paths', () => {
    api.signInProviders().subscribe();
    api.me().subscribe();
    api.adminOverview().subscribe();
    api.adminSyncRuns(20).subscribe();

    backend.expectOne('/api/v1/auth/providers').flush([]);
    backend.expectOne('/api/v1/me').flush({});
    backend.expectOne('/api/v1/admin/overview').flush({});
    backend.expectOne((request) => request.url === '/api/v1/admin/sync-runs' && request.params.get('limit') === '20').flush([]);
  });
});
