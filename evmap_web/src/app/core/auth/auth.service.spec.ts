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
    expect(auth.signedIn()).toBe(true);
    expect(auth.isAdmin()).toBe(true);
    expect(sessionStorage.getItem('evmap.pendingSignIn')).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });

  it('keeps no credential in page storage and picks the session up again after a page load', async () => {
    api.hasSession = true;

    expect(auth.signedIn()).toBe(false);
    expect((await auth.restore())?.id).toBe('acc-1');
    expect(auth.signedIn()).toBe(true);
    expect(sessionStorage.length).toBe(0);
  });

  it('stays signed out on a page load without a session', async () => {
    expect(await auth.restore()).toBeNull();
    expect(auth.signedIn()).toBe(false);
  });

  it('signs out through the backend, which removes the cookie', async () => {
    api.hasSession = true;
    await auth.restore();

    await auth.signOut();

    expect(auth.signedIn()).toBe(false);
    expect(api.hasSession).toBe(false);
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

  it('forgets an abandoned sign-in, so its answer can no longer complete', async () => {
    const state = (await leave()).searchParams.get('state')!;

    auth.abandon();

    expect(await auth.complete('google', new URLSearchParams({ code: 'the-code', state }))).toEqual({ error: 'state' });
    expect(api.exchanges).toHaveLength(0);
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

  it('signs out locally only after the backend confirmed the deletion', async () => {
    api.hasSession = true;
    await auth.refreshAccount();
    expect(auth.signedIn()).toBe(true);

    api.rejectDeletion = true;
    await expect(auth.deleteAccount()).rejects.toThrow();
    expect(auth.signedIn()).toBe(true);

    api.rejectDeletion = false;
    await auth.deleteAccount();
    expect(auth.signedIn()).toBe(false);
    expect(api.hasSession).toBe(false);
  });
});
