import { Routes } from '@angular/router';

/** The admin feature area. Phase 2's moderation queues join as further children. */
export const adminRoutes: Routes = [
  { path: '', loadComponent: () => import('./overview.page').then((m) => m.OverviewPage) },
];
