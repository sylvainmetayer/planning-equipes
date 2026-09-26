// « Nouveau lieu… », the last option of the location select of the two stand
// dialogs — the stand form and the guided creation: the location form opens
// over the dialog, and the place it created is chosen, with no need to leave
// the stand for another page.

import { MatDialog } from '@angular/material/dialog';
import { firstValueFrom } from 'rxjs';
import { ReferenceDataStore } from '../../core/reference-data.store';

/** The value of « Nouveau lieu… » in the location select: never an emplacement id. */
export const NEW_LOCATION = '\u0000nouveau-lieu';

/**
 * Opens the location form for a new place and answers its id, or `null` when
 * the form was dismissed. The form is loaded on demand: its map pulls
 * Leaflet, which the Stands chunk must not carry.
 */
export async function createLocation(
  dialog: MatDialog,
  store: ReferenceDataStore,
): Promise<string | null> {
  const known = new Set(store.emplacements().map((emplacement) => emplacement.id));
  const { EmplacementFormDialog } = await import('../emplacements/emplacement-form-dialog');
  const saved = await firstValueFrom(
    dialog
      .open(EmplacementFormDialog, {
        data: { emplacement: null },
        width: '40rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      })
      .afterClosed(),
  );
  if (!saved) {
    return null;
  }
  return store.emplacements().find((emplacement) => !known.has(emplacement.id))?.id ?? null;
}
