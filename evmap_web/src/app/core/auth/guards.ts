import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { AuthService } from './auth.service';

/**
 * Hides the admin area from everybody without the flag. Presentation only: every admin endpoint
 * checks the flag in the backend, which is the actual boundary (ADR 0018).
 */
export const adminGuard: CanMatchFn = async (_route, segments) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const target = '/' + segments.map((segment) => segment.path).join('/');
  if (!auth.signedIn()) return router.createUrlTree(['/login'], { queryParams: { returnUrl: target } });
  const account = auth.account() ?? (await auth.refreshAccount().catch(() => null));
  return account?.admin ? true : router.createUrlTree(['/forbidden']);
};
