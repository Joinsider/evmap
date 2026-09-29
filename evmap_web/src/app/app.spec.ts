import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { App } from './app';
import { EvmapApi } from './core/api/evmap-api';
import { AuthService } from './core/auth/auth.service';
import { FakeEvmapApi } from './testing/fake-evmap-api';

describe('App', () => {
  it('offers sign-in when signed out and signs out back to the start page', async () => {
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: new FakeEvmapApi() }, provideRouter([])] });
    const fixture = TestBed.createComponent(App);
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Anmelden');
    expect(fixture.nativeElement.textContent).not.toContain('Admin');

    const auth = TestBed.inject(AuthService);
    let state = '';
    await auth.begin({ provider: 'github', authorizationEndpoint: 'https://github.com/login/oauth/authorize', parameters: {}, pkce: false }, '/',
      (url) => (state = new URL(url).searchParams.get('state')!));
    await auth.complete('github', new URLSearchParams({ code: 'c', state }));
    fixture.detectChanges();
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('button.link')!.click();

    expect(auth.signedIn()).toBe(false);
    expect(navigate).toHaveBeenCalledWith('/');
  });
});
