import { Routes } from '@angular/router';

/** The admin feature area: the overview, the comment moderation queue (ADR 0020) and the station report queue (ADR 0021). */
export const adminRoutes: Routes = [
  {
    path: '',
    loadComponent: () => import('./admin-shell').then((m) => m.AdminShell),
    children: [
      { path: '', loadComponent: () => import('./overview.page').then((m) => m.OverviewPage) },
      { path: 'reports', loadComponent: () => import('./reports.page').then((m) => m.ReportsPage) },
      { path: 'station-reports', loadComponent: () => import('./station-reports.page').then((m) => m.StationReportsPage) },
    ],
  },
];
