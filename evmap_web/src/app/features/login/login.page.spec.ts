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

  it('shows which provider it is leaving for and locks the buttons meanwhile', async () => {
    vi.spyOn(TestBed.inject(AuthService), 'begin').mockReturnValue(new Promise(() => undefined));
    const fixture = await render();
    const page = fixture.nativeElement as HTMLElement;

    page.querySelectorAll('button')[0].click();
    await fixture.whenStable();

    const buttons = [...page.querySelectorAll('button')];
    expect(buttons.every((button) => button.disabled)).toBe(true);
    expect(buttons[0].getAttribute('aria-busy')).toBe('true');
    expect(buttons[0].querySelector('.spinner')).not.toBeNull();
    expect(buttons[1].querySelector('.spinner')).toBeNull();
    expect(page.querySelector('[role=status]')!.textContent).toContain('Weiter zu Apple');
  });

  it('is usable again when the browser brings it back after a cancelled sign-in', async () => {
    const auth = TestBed.inject(AuthService);
    vi.spyOn(auth, 'begin').mockResolvedValue();
    const abandon = vi.spyOn(auth, 'abandon');
    const fixture = await render();
    const page = fixture.nativeElement as HTMLElement;
    page.querySelector('button')!.click();
    await fixture.whenStable();

    // A fresh load is no return from the provider: nothing to undo.
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: false }));
    await fixture.whenStable();
    expect(page.querySelector('button')!.disabled).toBe(true);

    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
    await fixture.whenStable();

    expect([...page.querySelectorAll('button')].some((button) => button.disabled)).toBe(false);
    expect(page.querySelector('[role=status]')!.textContent!.trim()).toBe('');
    expect(abandon).toHaveBeenCalledOnce();
  });

  it('unlocks the buttons when the sign-in cannot even start', async () => {
    vi.spyOn(TestBed.inject(AuthService), 'begin').mockRejectedValue(new Error('no crypto'));
    const fixture = await render();
    const page = fixture.nativeElement as HTMLElement;

    page.querySelector('button')!.click();
    await fixture.whenStable();

    expect(page.querySelector('button')!.disabled).toBe(false);
  });
});
