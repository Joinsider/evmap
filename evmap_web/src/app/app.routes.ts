import { Routes } from '@angular/router';
import { adminGuard, signedInGuard } from './core/auth/guards';

/**
 * Feature areas load lazily (ADR 0018). The map is the start page and the station panel its child route, so opening
 * and closing a station keeps the map — and its loaded viewport — alive (ADR 0023).
 */
export const routes: Routes = [
  { path: 'login', loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage) },
  // The redirect URI registered with every provider; kept stable, it is also the iOS app's callback.
  { path: 'auth/callback/:provider', loadComponent: () => import('./features/login/callback.page').then((m) => m.CallbackPage) },
  { path: 'account', canMatch: [signedInGuard], loadComponent: () => import('./features/account/account.page').then((m) => m.AccountPage) },
  { path: 'forbidden', loadComponent: () => import('./features/home/forbidden.page').then((m) => m.ForbiddenPage) },
  { path: 'admin', canMatch: [adminGuard], loadChildren: () => import('./features/admin/admin.routes').then((m) => m.adminRoutes) },
  { path: '', loadChildren: () => import('./features/map/map.routes').then((m) => m.mapRoutes) },
  { path: '**', redirectTo: '' },
];
