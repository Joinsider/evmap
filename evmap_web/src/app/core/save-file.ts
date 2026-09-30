import { InjectionToken } from '@angular/core';

/** Hands a downloaded blob to the browser as a file save. */
export function saveFile(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  link.click();
  // Released on the next turn: the click has already handed the URL to the download.
  setTimeout(() => URL.revokeObjectURL(url));
}

/** Injected rather than imported, so tests can observe a save without a real download. */
export const FILE_SAVER = new InjectionToken<typeof saveFile>('FILE_SAVER', { providedIn: 'root', factory: () => saveFile });
