import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from './auth.service';

/** Adds the bearer token to API calls and drops it when the API says it is no longer valid. */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const token = auth.accessToken();
  if (!token || !request.url.startsWith('/api/')) return next(request);

  return next(request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })).pipe(
    catchError((error: unknown) => {
      // Expired after 12 h or signed with a rotated secret: the user has to sign in again.
      if (error instanceof HttpErrorResponse && error.status === 401) auth.signOut();
      return throwError(() => error);
    }),
  );
};
