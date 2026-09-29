import { TestBed } from '@angular/core/testing';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { EvmapApi } from '../api/evmap-api';
import { SignInProvider } from '../api/models';
import { AuthService } from './auth.service';

const google: SignInProvider = {
  provider: 'google',
  authorizationEndpoint: 'https://accounts.google.com/o/oauth2/v2/auth',
  parameters: { client_id: 'client', redirect_uri: 'https://evmap.example/auth/callback/google', response_type: 'code' },
  pkce: true,
};

describe('AuthService', () => {
  let api: FakeEvmapApi;
  let auth: AuthService;

  beforeEach(() => {
    sessionStorage.clear();
    api = new FakeEvmapApi();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
    auth = TestBed.inject(AuthService);
  });

  async function leave(provider = google): Promise<URL> {
    let target = '';
    await auth.begin(provider, '/admin', (url) => (target = url));
    return new URL(target);
  }

  it('sends the browser to the provider with state and a PKCE challenge', async () => {
    const url = await leave();

    expect(url.origin + url.pathname).toBe(google.authorizationEndpoint);
    expect(url.searchParams.get('client_id')).toBe('client');
    expect(url.searchParams.get('state')).toBeTruthy();
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('code_verifier')).toBeNull();
  });

  it('omits PKCE for a provider without it', async () => {
    const url = await leave({ ...google, provider: 'apple', pkce: false });

    expect(url.searchParams.get('code_challenge')).toBeNull();
  });

  it('exchanges the code with the stored verifier and signs in', async () => {
    api.account = { id: 'acc-1', admin: true, identities: [] };
    const state = (await leave()).searchParams.get('state')!;

    const result = await auth.complete('google', new URLSearchParams({ code: 'the-code', state }));

    expect(result).toEqual({ returnUrl: '/admin' });
    expect(api.exchanges[0].code).toBe('the-code');
    expect(api.exchanges[0].codeVerifier).toMatch(/^[A-Za-z0-9_-]{64}$/);
    expect(auth.accessToken()).toBe('access-token');
    expect(auth.isAdmin()).toBe(true);
    expect(sessionStorage.getItem('evmap.pendingSignIn')).toBeNull();
    expect(sessionStorage.getItem('evmap.accessToken')).toBe('access-token');
  });

  it('restores the token after a page load and forgets it on sign-out', () => {
    sessionStorage.setItem('evmap.accessToken', 'kept');
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
    const reloaded = TestBed.inject(AuthService);

    expect(reloaded.signedIn()).toBe(true);
    expect(reloaded.accessToken()).toBe('kept');

    reloaded.signOut();
    expect(sessionStorage.getItem('evmap.accessToken')).toBeNull();
  });

  it('refuses an answer whose state does not match, without exchanging it', async () => {
    await leave();

    expect(await auth.complete('google', new URLSearchParams({ code: 'the-code', state: 'forged' }))).toEqual({ error: 'state' });
    expect(api.exchanges).toHaveLength(0);
    expect(auth.signedIn()).toBe(false);
  });

  it('refuses a callback that no sign-in in this tab started', async () => {
    expect(await auth.complete('google', new URLSearchParams({ code: 'c', state: 's' }))).toEqual({ error: 'state' });
  });

  it('reports a cancelled sign-in as a provider error', async () => {
    const state = (await leave()).searchParams.get('state')!;

    expect(await auth.complete('google', new URLSearchParams({ error: 'access_denied', state }))).toEqual({ error: 'provider' });
  });

  it('stays signed out when the backend refuses the code', async () => {
    api.rejectExchange = true;
    const state = (await leave()).searchParams.get('state')!;

    expect(await auth.complete('google', new URLSearchParams({ code: 'c', state }))).toEqual({ error: 'exchange' });
    expect(auth.signedIn()).toBe(false);
  });
});
