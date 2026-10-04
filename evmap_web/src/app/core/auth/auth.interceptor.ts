import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

const ME = '/api/v1/me';

/**
 * Notices when the API says the session cookie is gone or no longer valid. The cookie itself needs
 * no handling here: the browser sends it, and Angular's built-in XSRF support echoes the CSRF cookie
 * as a header on writes.
 *
 * Only `/me` decides that the sign-in is over. A 401 anywhere else is checked against `/me` first:
 * one refused request is not proof the session ended, and signing out on it left people signed out
 * in the page while their cookie was still valid.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  return next(request).pipe(
    catchError((error: unknown) => {
      // Expired after 12 h or signed with a rotated secret: the user has to sign in again.
      if (error instanceof HttpErrorResponse && error.status === 401 && request.url.startsWith('/api/')) {
        if (request.url === ME) auth.forget();
        else void auth.refreshAccount();
      }
      return throwError(() => error);
    }),
  );
};
