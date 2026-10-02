import { Component, OnDestroy, inject, input, output, signal } from '@angular/core';
import { MapEngine, Place, PlaceSuggestion } from '../../core/map/map-engine';
import { Viewport } from './domain/viewport';

/** Typing pauses this long before a request: every autocomplete call draws on MapKit's daily service quota. */
export const SEARCH_DEBOUNCE_MS = 300;
export const SEARCH_MIN_LENGTH = 3;

/**
 * Address and place search through MapKit JS (ADR 0023, like ADR 0011 on iOS). Choosing a suggestion moves the map;
 * nothing else happens and nothing is kept — the text goes to Apple and nowhere else, and is never logged.
 */
@Component({
  selector: 'app-place-search',
  template: `
    <div class="search" role="search">
      <input
        type="search"
        [value]="query()"
        (input)="typed($any($event.target).value)"
        (keydown.escape)="clear()"
        i18n-placeholder="@@search.prompt"
        placeholder="Adresse oder Ort suchen"
        i18n-aria-label="@@search.prompt"
        aria-label="Adresse oder Ort suchen"
        autocomplete="off"
      />
      @if (suggestions().length) {
        <ul class="suggestions" role="listbox">
          @for (suggestion of suggestions(); track $index) {
            <li>
              <button type="button" (click)="choose(suggestion)">
                <strong>{{ suggestion.title }}</strong>
                @if (suggestion.subtitle) {
                  <span>{{ suggestion.subtitle }}</span>
                }
              </button>
            </li>
          }
        </ul>
      } @else if (notFound()) {
        <p class="hint" role="status" i18n="@@search.error.notFound">Zu dieser Adresse wurde kein Ort gefunden.</p>
      }
    </div>
  `,
  styles: `
    .search { position: relative; }
    input {
      width: 100%; box-sizing: border-box; padding: 0.5rem 0.75rem; font: inherit;
      background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 0.5rem;
      box-shadow: 0 1px 4px rgb(0 0 0 / 15%);
    }
    .suggestions, .hint {
      position: absolute; top: calc(100% + 0.25rem); left: 0; right: 0; margin: 0; padding: 0.25rem 0; list-style: none;
      background: var(--surface); border: 1px solid var(--border); border-radius: 0.5rem; box-shadow: 0 2px 8px rgb(0 0 0 / 20%);
    }
    .hint { padding: 0.5rem 0.75rem; font-size: 0.875rem; color: var(--muted); }
    button {
      display: flex; flex-direction: column; align-items: flex-start; width: 100%; padding: 0.375rem 0.75rem;
      background: none; border: none; color: var(--text); text-align: left;
    }
    button:hover, button:focus-visible { background: var(--bg); }
    span { font-size: 0.8125rem; color: var(--muted); }
  `,
})
export class PlaceSearch implements OnDestroy {
  private readonly engine = inject(MapEngine);

  readonly near = input.required<Viewport>();
  readonly chosen = output<Place>();

  protected readonly query = signal('');
  protected readonly suggestions = signal<PlaceSuggestion[]>([]);
  protected readonly notFound = signal(false);
  private timer: ReturnType<typeof setTimeout> | null = null;
  /** Only the answer to the newest text counts; a slow earlier answer must not replace it. */
  private generation = 0;

  protected typed(text: string) {
    this.query.set(text);
    this.notFound.set(false);
    if (this.timer) clearTimeout(this.timer);
    const generation = ++this.generation;
    if (text.trim().length < SEARCH_MIN_LENGTH) {
      this.suggestions.set([]);
      return;
    }
    this.timer = setTimeout(async () => {
      const results = await this.engine.autocomplete(text.trim(), this.near()).catch(() => []);
      if (generation !== this.generation) return;
      this.suggestions.set(results);
      this.notFound.set(results.length === 0);
    }, SEARCH_DEBOUNCE_MS);
  }

  protected async choose(suggestion: PlaceSuggestion) {
    this.suggestions.set([]);
    const place = await this.engine.resolve(suggestion).catch(() => null);
    if (!place) {
      this.notFound.set(true);
      return;
    }
    this.query.set(suggestion.title);
    this.chosen.emit(place);
  }

  protected clear() {
    ++this.generation;
    this.query.set('');
    this.suggestions.set([]);
    this.notFound.set(false);
  }

  ngOnDestroy() {
    if (this.timer) clearTimeout(this.timer);
  }
}
