import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { AuthService } from '../../core/auth/auth.service';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { CallbackPage } from './callback.page';

describe('CallbackPage', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: new FakeEvmapApi() }, provideRouter([])] });
  });

  async function render() {
    const fixture = TestBed.createComponent(CallbackPage);
    fixture.componentRef.setInput('provider', 'google');
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture;
  }

  it('continues to the return path, replacing the callback in the history', async () => {
    vi.spyOn(TestBed.inject(AuthService), 'complete').mockResolvedValue({ returnUrl: '/admin' });
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    await render();

    expect(navigate).toHaveBeenCalledWith('/admin', { replaceUrl: true });
  });

  it('explains a failed sign-in and offers to retry', async () => {
    vi.spyOn(TestBed.inject(AuthService), 'complete').mockResolvedValue({ error: 'state' });

    const text = (await render()).nativeElement.textContent;

    expect(text).toContain('abgelaufen');
    expect(text).toContain('Erneut anmelden');
  });
});
