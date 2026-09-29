import { Routes } from '@angular/router';
import { adminGuard } from './core/auth/guards';

/**
 * Feature areas load lazily, so the later user web app (map, stations, comments) joins as further
 * entries here without restructuring (ADR 0018).
 */
export const routes: Routes = [
  { path: '', loadComponent: () => import('./features/home/home.page').then((m) => m.HomePage) },
  { path: 'login', loadComponent: () => import('./features/login/login.page').then((m) => m.LoginPage) },
  // The redirect URI registered with every provider; kept stable, it is also the iOS app's callback.
  { path: 'auth/callback/:provider', loadComponent: () => import('./features/login/callback.page').then((m) => m.CallbackPage) },
  { path: 'forbidden', loadComponent: () => import('./features/home/forbidden.page').then((m) => m.ForbiddenPage) },
  { path: 'admin', canMatch: [adminGuard], loadChildren: () => import('./features/admin/admin.routes').then((m) => m.adminRoutes) },
  { path: '**', redirectTo: '' },
];
