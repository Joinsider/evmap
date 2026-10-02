import { TestBed } from '@angular/core/testing';
import { MapEngine, Place } from '../../core/map/map-engine';
import { FakeMapEngine } from '../../testing/fake-map-engine';
import { START_VIEWPORT } from './domain/viewport';
import { PlaceSearch, SEARCH_DEBOUNCE_MS } from './place-search';

const wait = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));

describe('PlaceSearch', () => {
  let engine: FakeMapEngine;

  beforeEach(() => {
    engine = new FakeMapEngine();
    TestBed.configureTestingModule({ providers: [{ provide: MapEngine, useValue: engine }] });
  });

  async function open() {
    const fixture = TestBed.createComponent(PlaceSearch);
    fixture.componentRef.setInput('near', START_VIEWPORT);
    const chosen: Place[] = [];
    fixture.componentInstance.chosen.subscribe((place) => chosen.push(place));
    await fixture.whenStable();
    return { fixture, chosen, input: (fixture.nativeElement as HTMLElement).querySelector('input')! };
  }

  function type(input: HTMLInputElement, text: string) {
    input.value = text;
    input.dispatchEvent(new Event('input'));
  }

  it('waits for a pause and three characters before asking, and asks once', async () => {
    const { input } = await open();
    type(input, 'St');
    type(input, 'Stu');
    type(input, 'Stut');
    await wait(SEARCH_DEBOUNCE_MS + 50);

    expect(engine.queries).toEqual(['Stut']);
  });

  it('moves to the chosen place and nothing else', async () => {
    engine.suggestions = [{ title: 'Stuttgart', subtitle: 'Baden-Württemberg', ref: {} }];
    engine.places.set('Stuttgart', { name: 'Stuttgart', latitude: 48.78, longitude: 9.18, latitudeSpan: 0.3, longitudeSpan: 0.4 });
    const { fixture, chosen, input } = await open();
    type(input, 'Stuttgart');
    await wait(SEARCH_DEBOUNCE_MS + 50);
    fixture.detectChanges();

    (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('.suggestions button')!.click();
    await wait(0);

    expect(chosen).toEqual([{ name: 'Stuttgart', latitude: 48.78, longitude: 9.18, latitudeSpan: 0.3, longitudeSpan: 0.4 }]);
  });

  it('says when nothing was found', async () => {
    const { fixture, input } = await open();
    type(input, 'Xyzzy');
    await wait(SEARCH_DEBOUNCE_MS + 50);
    fixture.detectChanges();

    expect((fixture.nativeElement as HTMLElement).textContent).toContain('kein Ort gefunden');
  });
});
