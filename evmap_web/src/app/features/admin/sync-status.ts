import { SyncRunStatus } from '../../core/api/models';

export function syncStatusLabel(status: SyncRunStatus): string {
  switch (status) {
    case 'RUNNING':
      return $localize`:@@sync.running:Läuft`;
    case 'SUCCEEDED':
      return $localize`:@@sync.succeeded:Erfolgreich`;
    case 'PARTIAL':
      return $localize`:@@sync.partial:Unvollständig`;
    case 'FAILED':
      return $localize`:@@sync.failed:Fehlgeschlagen`;
    case 'SKIPPED':
      return $localize`:@@sync.skipped:Übersprungen`;
  }
}
