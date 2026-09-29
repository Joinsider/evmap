import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, inject, provideAppInitializer, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { routes } from './app.routes';
import { EvmapApi } from './core/api/evmap-api';
import { RestEvmapApi } from './core/api/rest-evmap-api';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(withInterceptors([authInterceptor])),
    { provide: EvmapApi, useClass: RestEvmapApi },
    // A full page load starts without state; the session cookie tells the backend who this is.
    provideAppInitializer(() => inject(AuthService).restore()),
  ],
};
