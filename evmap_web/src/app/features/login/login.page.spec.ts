import { TestBed } from '@angular/core/testing';
import { throwError } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { AuthService } from '../../core/auth/auth.service';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { LoginPage } from './login.page';

describe('LoginPage', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    api = new FakeEvmapApi();
    api.providers = [
      { provider: 'apple', authorizationEndpoint: 'https://appleid.apple.com/auth/authorize', parameters: {}, pkce: false },
      { provider: 'github', authorizationEndpoint: 'https://github.com/login/oauth/authorize', parameters: {}, pkce: true },
    ];
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }] });
  });

  async function render(returnUrl?: string) {
    const fixture = TestBed.createComponent(LoginPage);
    if (returnUrl) fixture.componentRef.setInput('returnUrl', returnUrl);
    await fixture.whenStable();
    return fixture;
  }

  it('offers one button per provider and starts that provider with an in-app return path', async () => {
    const begin = vi.spyOn(TestBed.inject(AuthService), 'begin').mockResolvedValue();
    const fixture = await render('/admin');
    const buttons = (fixture.nativeElement as HTMLElement).querySelectorAll('button');

    expect([...buttons].map((button) => button.textContent!.trim())).toEqual(['Mit Apple anmelden', 'Mit GitHub anmelden']);
    buttons[1].click();
    expect(begin).toHaveBeenCalledWith(api.providers[1], '/admin');
  });

  it('never returns to another site after signing in', async () => {
    const begin = vi.spyOn(TestBed.inject(AuthService), 'begin').mockResolvedValue();
    const fixture = await render('//evil.example/phish');

    (fixture.nativeElement as HTMLElement).querySelector('button')!.click();
    expect(begin).toHaveBeenCalledWith(api.providers[0], '/');
  });

  it('says so when no provider is configured or the backend is unreachable', async () => {
    api.providers = [];
    expect((await render()).nativeElement.textContent).toContain('noch kein Anmeldeverfahren');

    TestBed.resetTestingModule();
    const failing = new FakeEvmapApi();
    failing.signInProviders = () => throwError(() => new Error('offline'));
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: failing }] });
    expect((await render()).nativeElement.textContent).toContain('nicht erreichbar');
  });
});
