import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { EvmapApi } from '../api/evmap-api';
import { authInterceptor } from './auth.interceptor';
import { AuthService } from './auth.service';

describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let auth: AuthService;

  beforeEach(async () => {
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [
        { provide: EvmapApi, useValue: new FakeEvmapApi() },
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    let state = '';
    await auth.begin({ provider: 'google', authorizationEndpoint: 'https://idp.example/auth', parameters: {}, pkce: false }, '/',
      (target) => (state = new URL(target).searchParams.get('state')!));
    await auth.complete('google', new URLSearchParams({ code: 'c', state }));
  });

  it('adds the bearer token to API calls only', () => {
    http.get('/api/v1/me').subscribe();
    http.get('https://elsewhere.example/data').subscribe();

    expect(backend.expectOne('/api/v1/me').request.headers.get('Authorization')).toBe('Bearer access-token');
    expect(backend.expectOne('https://elsewhere.example/data').request.headers.has('Authorization')).toBe(false);
  });

  it('signs out when the API no longer accepts the token', () => {
    http.get('/api/v1/me').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.signedIn()).toBe(false);
  });
});
