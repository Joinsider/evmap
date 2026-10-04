import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { AuthService } from '../../core/auth/auth.service';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { StationReportForm } from './station-report-form';

describe('StationReportForm', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    api = new FakeEvmapApi();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }, provideRouter([])] });
  });

  async function open(signedIn: boolean) {
    api.hasSession = signedIn;
    await TestBed.inject(AuthService).restore();
    const fixture = TestBed.createComponent(StationReportForm);
    fixture.componentRef.setInput('stationId', 's1');
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  }

  function button(root: HTMLElement, label: string) {
    return Array.from(root.querySelectorAll('button')).find((candidate) => candidate.textContent!.trim() === label) as HTMLButtonElement;
  }

  async function send(fixture: { whenStable(): Promise<unknown>; detectChanges(): void }, root: HTMLElement) {
    button(root, 'Senden').click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('asks signed-out people to sign in and come back to the station', async () => {
    const { root } = await open(false);

    const link = root.querySelector('a')!;
    expect(link.textContent).toContain('Zum Melden eines Fehlers bitte anmelden.');
    expect(link.getAttribute('href')).toBe('/login?returnUrl=%2Fstation%2Fs1');
  });

  it('sends a reason with a trimmed note and thanks the reporter', async () => {
    const { fixture, root } = await open(true);

    button(root, 'Fehler melden').click();
    fixture.detectChanges();
    expect(root.textContent).toContain('Bitte keine persönlichen Daten angeben.');
    expect(button(root, 'Senden').disabled).toBe(true);

    const radios = Array.from(root.querySelectorAll('input[type="radio"]')) as HTMLInputElement[];
    expect(radios).toHaveLength(5);
    radios[3].click();
    const note = root.querySelector('textarea')!;
    expect(note.maxLength).toBe(500);
    note.value = '  Display dunkel ';
    note.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await send(fixture, root);

    expect(api.calls).toEqual(['reportStation:s1:defective:Display dunkel']);
    expect(root.textContent).toContain('Danke!');
    expect(root.querySelector('form')).toBeNull();
  });

  it('leaves the note out when empty, and keeps the form with a message when sending fails', async () => {
    const { fixture, root } = await open(true);
    api.rejectWrites = true;

    button(root, 'Fehler melden').click();
    fixture.detectChanges();
    (root.querySelector('input[type="radio"]') as HTMLInputElement).click();
    fixture.detectChanges();
    await send(fixture, root);
    expect(root.textContent).toContain('Die Meldung konnte nicht gesendet werden.');

    api.rejectWrites = false;
    await send(fixture, root);
    expect(api.calls).toEqual(['reportStation:s1:gone:', 'reportStation:s1:gone:']);

    expect(root.textContent).toContain('Danke!');
  });

  it('closes again on cancel', async () => {
    const { fixture, root } = await open(true);

    button(root, 'Fehler melden').click();
    fixture.detectChanges();
    button(root, 'Abbrechen').click();
    fixture.detectChanges();

    expect(root.querySelector('form')).toBeNull();
    expect(button(root, 'Fehler melden')).toBeTruthy();
  });
});
