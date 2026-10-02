import { Routes } from '@angular/router';
import { MapEngine } from '../../core/map/map-engine';
import { MapKitEngine } from '../../core/map/mapkit-engine';
import { MapPage } from './map.page';

export const mapRoutes: Routes = [
  {
    path: '',
    component: MapPage,
    // Provided here, not in the app config: only the map area loads MapKit JS.
    providers: [{ provide: MapEngine, useClass: MapKitEngine }],
    children: [{ path: 'station/:id', loadComponent: () => import('./station.panel').then((m) => m.StationPanel) }],
  },
];
