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
  const account = auth.account() ?? (await auth.refreshAccount());
  if (!account) return router.createUrlTree(['/login'], { queryParams: { returnUrl: target } });
  return account.admin ? true : router.createUrlTree(['/forbidden']);
};

/** Sends people who are not signed in to the login page and back afterwards. Presentation only, like {@link adminGuard}. */
export const signedInGuard: CanMatchFn = async (_route, segments) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const target = '/' + segments.map((segment) => segment.path).join('/');
  const account = auth.account() ?? (await auth.refreshAccount());
  return account ? true : router.createUrlTree(['/login'], { queryParams: { returnUrl: target } });
};
