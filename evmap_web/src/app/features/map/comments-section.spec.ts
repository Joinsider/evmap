import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { throwError } from 'rxjs';
import { EvmapApi } from '../../core/api/evmap-api';
import { StationComment } from '../../core/api/models';
import { AuthService } from '../../core/auth/auth.service';
import { FakeEvmapApi } from '../../testing/fake-evmap-api';
import { CommentsSection } from './comments-section';

const comment = (id: string, owned: boolean, extra: Partial<StationComment> = {}): StationComment => ({
  id,
  body: `Kommentar ${id}`,
  createdAt: '2026-09-28T10:00:00Z',
  updatedAt: '2026-09-28T10:00:00Z',
  ownedByCurrentUser: owned,
  ...extra,
});

const tick = () => new Promise((resolve) => setTimeout(resolve));

describe('CommentsSection', () => {
  let api: FakeEvmapApi;

  beforeEach(() => {
    api = new FakeEvmapApi();
    TestBed.configureTestingModule({ providers: [{ provide: EvmapApi, useValue: api }, provideRouter([])] });
  });

  async function open(signedIn: boolean) {
    api.hasSession = signedIn;
    await TestBed.inject(AuthService).restore();
    const fixture = TestBed.createComponent(CommentsSection);
    fixture.componentRef.setInput('stationId', 's1');
    await settle(fixture);
    const root = fixture.nativeElement as HTMLElement;
    return { fixture, root };
  }

  async function settle(fixture: { whenStable(): Promise<unknown>; detectChanges(): void }) {
    await fixture.whenStable();
    fixture.detectChanges();
    await tick();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function button(root: HTMLElement, label: string) {
    const found = Array.from(root.querySelectorAll('button')).find((candidate) => candidate.textContent!.trim() === label);
    if (!found) throw new Error(`no button "${label}"`);
    return found as HTMLButtonElement;
  }

  function type(root: HTMLElement, selector: string, value: string) {
    const field = root.querySelector(selector) as HTMLInputElement | HTMLTextAreaElement;
    field.value = value;
    field.dispatchEvent(new Event('input'));
  }

  it('lists comments with date, price and experience, and asks signed-out readers to sign in', async () => {
    api.commentsByStation.set('s1', [comment('c1', false, { paidPriceCents: 2140, experience: 'Schnell' })]);
    const { root } = await open(false);
    const text = root.textContent!.replace(/\s+/g, ' ').replace(/ /g, ' ');

    expect(text).toContain('Kommentar c1');
    expect(text).toContain('Bezahlt: 21,40 €');
    expect(text).toContain('Schnell');
    const login = root.querySelector('a')!;
    expect(login.textContent).toContain('Zum Kommentieren bitte anmelden.');
    expect(login.getAttribute('href')).toBe('/login?returnUrl=%2Fstation%2Fs1');
    expect(root.querySelectorAll('button')).toHaveLength(0);
  });

  it('writes a comment with the price in euros and shows it after reloading the list', async () => {
    const { fixture, root } = await open(true);

    button(root, 'Kommentar schreiben').click();
    fixture.detectChanges();
    const save = button(root, 'Speichern');
    expect(save.disabled).toBe(true);

    type(root, 'textarea', 'Lief gut');
    type(root, 'input[inputmode="decimal"]', 'viel');
    fixture.detectChanges();
    expect(root.textContent).toContain('Bitte den Preis als Betrag angeben');
    expect(save.disabled).toBe(true);

    type(root, 'input[inputmode="decimal"]', '21,40');
    type(root, 'input[type="text"]:not([inputmode])', 'Schnell');
    fixture.detectChanges();
    expect(save.disabled).toBe(false);
    save.click();
    await settle(fixture);

    expect(api.calls).toEqual(['createComment:s1']);
    expect(api.commentsByStation.get('s1')![0]).toMatchObject({ body: 'Lief gut', paidPriceCents: 2140, experience: 'Schnell' });
    expect(root.textContent).toContain('Lief gut');
    expect(root.querySelector('form')).toBeNull();
  });

  it('keeps the text and says so when saving fails', async () => {
    const { fixture, root } = await open(true);
    api.rejectWrites = true;

    button(root, 'Kommentar schreiben').click();
    fixture.detectChanges();
    type(root, 'textarea', 'Lief gut');
    fixture.detectChanges();
    button(root, 'Speichern').click();
    await settle(fixture);

    expect(root.textContent).toContain('Das hat nicht geklappt');
    expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('Lief gut');

    button(root, 'Abbrechen').click();
    fixture.detectChanges();
    expect(root.querySelector('form')).toBeNull();
    expect(root.textContent).not.toContain('Das hat nicht geklappt');
  });

  it('edits the own comment with its stored values in the form', async () => {
    api.commentsByStation.set('s1', [comment('mine', true, { paidPriceCents: 2140, experience: 'Schnell' })]);
    const { fixture, root } = await open(true);

    button(root, 'Bearbeiten').click();
    fixture.detectChanges();
    expect((root.querySelector('textarea') as HTMLTextAreaElement).value).toBe('Kommentar mine');
    expect((root.querySelector('input[inputmode="decimal"]') as HTMLInputElement).value).toBe('21,40');
    expect(root.textContent).not.toContain('Kommentar schreiben');

    type(root, 'textarea', 'Neu');
    type(root, 'input[inputmode="decimal"]', '');
    fixture.detectChanges();
    button(root, 'Speichern').click();
    await settle(fixture);

    expect(api.calls).toEqual(['updateComment:mine']);
    expect(api.commentsByStation.get('s1')![0]).toMatchObject({ body: 'Neu', paidPriceCents: undefined, experience: 'Schnell' });
  });

  it('deletes the own comment only after confirming', async () => {
    api.commentsByStation.set('s1', [comment('mine', true)]);
    const { fixture, root } = await open(true);

    button(root, 'Löschen').click();
    fixture.detectChanges();
    expect(root.textContent).toContain('Kommentar löschen?');
    button(root, 'Abbrechen').click();
    fixture.detectChanges();
    expect(api.calls).toEqual([]);

    button(root, 'Löschen').click();
    fixture.detectChanges();
    button(root, 'Löschen').click();
    await settle(fixture);
    expect(api.calls).toEqual(['deleteComment:mine']);
    expect(root.textContent).toContain('Noch keine Kommentare.');
  });

  it('reports someone else\'s comment with a reason, after which it is gone for this reader', async () => {
    api.commentsByStation.set('s1', [comment('other', false), comment('mine', true)]);
    const { fixture, root } = await open(true);

    expect(root.querySelectorAll('button').length).toBeGreaterThan(0);
    button(root, 'Melden oder blockieren').click();
    fixture.detectChanges();
    expect(root.textContent).toContain('Gemeldete Kommentare werden dir nicht mehr angezeigt');
    button(root, 'Spam oder Werbung').click();
    await settle(fixture);

    expect(api.calls).toEqual(['reportComment:other:spam']);
    expect(root.textContent).not.toContain('Kommentar other');
    expect(root.textContent).toContain('Kommentar mine');
  });

  it('blocks the author of a comment, and reports a failure without hiding it', async () => {
    api.commentsByStation.set('s1', [comment('other', false)]);
    const { fixture, root } = await open(true);
    api.rejectWrites = true;

    button(root, 'Melden oder blockieren').click();
    fixture.detectChanges();
    button(root, 'Autor blockieren').click();
    await settle(fixture);
    expect(root.textContent).toContain('Das hat nicht geklappt');
    expect(root.textContent).toContain('Kommentar other');

    api.rejectWrites = false;
    button(root, 'Autor blockieren').click();
    await settle(fixture);
    expect(api.calls).toEqual(['blockAuthor:other', 'blockAuthor:other']);
    expect(root.textContent).not.toContain('Kommentar other');
  });

  it('offers no moderation of the own comments and none signed out', async () => {
    api.commentsByStation.set('s1', [comment('mine', true)]);
    const { root } = await open(true);

    expect(root.textContent).not.toContain('Melden oder blockieren');
  });

  it('says when the comments cannot be loaded', async () => {
    api.comments = () => throwError(() => new Error('down'));
    const { root } = await open(false);

    expect(root.textContent).toContain('Kommentare konnten nicht geladen werden.');
  });
});
