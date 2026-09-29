import { Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { Observable, catchError, map, of } from 'rxjs';

/** A one-shot request as the template sees it. */
export type Loaded<T> = { state: 'loading' } | { state: 'failed' } | { state: 'ready'; value: T };

/** Subscribes in the current injection context and never errors: a failure becomes a state. */
export function load<T>(source: Observable<T>): Signal<Loaded<T>> {
  return toSignal(
    source.pipe(
      map((value): Loaded<T> => ({ state: 'ready', value })),
      catchError(() => of<Loaded<T>>({ state: 'failed' })),
    ),
    { initialValue: { state: 'loading' } as Loaded<T> },
  );
}
