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
    const api = new FakeEvmapApi();
    api.hasSession = true;
    TestBed.configureTestingModule({
      providers: [
        { provide: EvmapApi, useValue: api },
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
    await auth.restore();
  });

  it('never puts a credential on a request: the cookie is the browser\'s business', () => {
    http.get('/api/v1/me').subscribe();

    expect(backend.expectOne('/api/v1/me').request.headers.has('Authorization')).toBe(false);
  });

  it('forgets the sign-in when the API no longer accepts the session', () => {
    expect(auth.signedIn()).toBe(true);
    http.get('/api/v1/me').subscribe({ error: () => undefined });
    backend.expectOne('/api/v1/me').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.signedIn()).toBe(false);
  });

  it('ignores a 401 from somewhere that is not our API', () => {
    http.get('https://elsewhere.example/data').subscribe({ error: () => undefined });
    backend.expectOne('https://elsewhere.example/data').flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(auth.signedIn()).toBe(true);
  });
});
