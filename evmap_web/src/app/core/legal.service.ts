import { Injectable, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { catchError, map, of } from 'rxjs';
import { EvmapApi } from './api/evmap-api';

/**
 * Where the operator published the privacy policy (ADR 0020). The URL is backend configuration, so
 * it can change without a new build; when there is none, or the request fails, no link is shown.
 */
@Injectable({ providedIn: 'root' })
export class LegalService {
  private readonly api = inject(EvmapApi);

  readonly privacyPolicyUrl = toSignal(
    this.api.legal().pipe(
      map((legal) => legal.privacyPolicyUrl ?? null),
      catchError(() => of(null)),
    ),
    { initialValue: null },
  );
}
