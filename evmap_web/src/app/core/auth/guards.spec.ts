import { TestBed } from '@angular/core/testing';
import { Router, UrlSegment, UrlTree } from '@angular/router';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { EvmapApi } from '../api/evmap-api';
import { AuthService } from './auth.service';
import { adminGuard } from './guards';

describe('adminGuard', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    sessionStorage.clear();
    api = new FakeEvmapApi();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
  });

  const run = () => TestBed.runInInjectionContext(() => adminGuard({}, [new UrlSegment('admin', {})], {} as never));
  const url = (result: unknown) => TestBed.inject(Router).serializeUrl(result as UrlTree);

  async function signIn(admin: boolean) {
    api.account = { id: 'acc-1', admin, identities: [] };
    const auth = TestBed.inject(AuthService);
    let state = '';
    await auth.begin({ provider: 'github', authorizationEndpoint: 'https://github.com/login/oauth/authorize', parameters: {}, pkce: true }, '/',
      (target) => (state = new URL(target).searchParams.get('state')!));
    await auth.complete('github', new URLSearchParams({ code: 'c', state }));
  }

  it('sends anonymous visitors to the login, remembering where they wanted to go', async () => {
    expect(url(await run())).toBe('/login?returnUrl=%2Fadmin');
  });

  it('shows signed-in accounts without the flag the forbidden page', async () => {
    await signIn(false);
    expect(url(await run())).toBe('/forbidden');
  });

  it('lets admins through', async () => {
    await signIn(true);
    expect(await run()).toBe(true);
  });
});
