import { Injectable, signal } from '@angular/core';

/**
 * Where the open station is, so the map can bring a station opened by link into view. Provided by the map page and
 * written by the station panel once it has loaded the station; the panel knows the id, only the detail knows where.
 */
@Injectable()
export class StationSelection {
  readonly position = signal<{ latitude: number; longitude: number } | null>(null);
}
