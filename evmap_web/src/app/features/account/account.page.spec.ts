import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { EvmapApi } from '../../core/api/evmap-api';
import { FILE_SAVER } from '../../core/save-file';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { AccountPage } from './account.page';

describe('AccountPage', () => {
  let api: FakeEvmapApi;
  const save = vi.fn();

  beforeEach(() => {
    save.mockClear();
    api = new FakeEvmapApi();
    api.hasSession = true;
    api.account = { id: 'acc-1', admin: false, identities: [{ provider: 'google', email: 'ada@example.org' }, { provider: 'apple' }] };
    api.contributionsData = {
      comments: [{ id: 'c1', stationId: 's1', stationName: 'EnBW Stuttgart', body: 'Lädt schnell', createdAt: '2026-09-01T10:00:00Z', updatedAt: '2026-09-01T10:00:00Z' }],
      reports: [{ id: 'r1', reason: 'spam', status: 'open', stationName: 'Ionity', createdAt: '2026-09-02T10:00:00Z' }],
    };
    api.blockList = [{ id: 'b1', createdAt: '2026-09-03T10:00:00Z' }];
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }, { provide: FILE_SAVER, useValue: save }, provideRouter([])] });
  });

  async function open() {
    const { AuthService } = await import('../../core/auth/auth.service');
    await TestBed.inject(AuthService).refreshAccount();
    const fixture = TestBed.createComponent(AccountPage);
    await fixture.whenStable();
    return fixture;
  }

  const buttons = (root: HTMLElement) => Array.from(root.querySelectorAll('button'));
  const button = (root: HTMLElement, label: string) => buttons(root).find((b) => b.textContent!.includes(label))!;

  it('lists sign-ins, contributions and blocks', async () => {
    const fixture = await open();
    const text = (fixture.nativeElement as HTMLElement).textContent!;

    expect(text).toContain('Google');
    expect(text).toContain('ada@example.org');
    expect(text).toContain('Lädt schnell');
    expect(text).toContain('Spam oder Werbung');
    expect(text).toContain('Wird geprüft');
    expect(text).toContain('Blockiert am');
  });

  it('lifts a block and hides it at once', async () => {
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;

    button(root, 'Aufheben').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(api.calls).toContain('unblock:b1');
    expect(root.textContent).not.toContain('Blockiert am');
    expect(root.textContent).toContain('Du hast niemanden blockiert.');
  });

  it('downloads the export as a file', async () => {
    const fixture = await open();

    button(fixture.nativeElement, 'Daten herunterladen').click();
    await fixture.whenStable();

    expect(api.calls).toContain('exportData');
    expect(save).toHaveBeenCalledWith(api.exportBlob, 'evmap-export.json');
  });

  it('deletes the account only after a second, explicit confirmation, then leaves for the start page', async () => {
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    button(root, 'Konto löschen …').click();
    fixture.detectChanges();
    expect(api.calls).not.toContain('deleteAccount');
    expect(root.textContent).toContain('wirklich endgültig löschen');

    button(root, 'Abbrechen').click();
    fixture.detectChanges();
    expect(buttons(root).some((b) => b.textContent!.includes('Konto löschen …'))).toBe(true);

    button(root, 'Konto löschen …').click();
    fixture.detectChanges();
    button(root, 'Endgültig löschen').click();
    await fixture.whenStable();

    expect(api.calls).toContain('deleteAccount');
    expect(navigate).toHaveBeenCalledWith('/');
  });

  it('stays put and says so when the deletion fails', async () => {
    api.rejectDeletion = true;
    const fixture = await open();
    const root = fixture.nativeElement as HTMLElement;
    const navigate = vi.spyOn(TestBed.inject(Router), 'navigateByUrl').mockResolvedValue(true);

    button(root, 'Konto löschen …').click();
    fixture.detectChanges();
    button(root, 'Endgültig löschen').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(root.textContent).toContain('konnte nicht gelöscht werden');
    expect(navigate).not.toHaveBeenCalled();
  });

  it('shows the privacy policy link only when the operator configured one', async () => {
    let fixture = await open();
    expect((fixture.nativeElement as HTMLElement).querySelector('a[href="https://evmap.example/privacy"]')).toBeNull();

    TestBed.resetTestingModule();
    api.legalData = { privacyPolicyUrl: 'https://evmap.example/privacy' };
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }, { provide: FILE_SAVER, useValue: save }, provideRouter([])] });
    fixture = await open();
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).querySelector('a[href="https://evmap.example/privacy"]')).not.toBeNull();
  });
});
