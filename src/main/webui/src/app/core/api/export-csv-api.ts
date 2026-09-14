// The referential CSV export: `/api/reference-data/export-csv*`. See
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ExportCsvTarget, VolumesExportCsv } from '../models';

@Injectable({ providedIn: 'root' })
export class ExportCsvApi {
  private readonly api = inject(ApiService);

  /** How many rows each referential would write, so the screen says what it offers. */
  volumes(): Promise<VolumesExportCsv> {
    return this.api.get<VolumesExportCsv>('/api/reference-data/export-csv/volumes');
  }

  /**
   * Downloads the chosen referentials as a zip of CSV files.
   *
   * <p>The six flags travel in the query string rather than a body: a
   * download is a navigation, and a `GET` is what a browser can save.</p>
   */
  telecharger(targets: readonly ExportCsvTarget[]): Promise<string> {
    const demande = (target: ExportCsvTarget) => targets.includes(target);
    // All six flags spelled out, always all six: that is what lets
    // `check-api-contract` confront them with the contract, where a query
    // assembled by `URLSearchParams` tells it nothing at all.
    return this.api.downloadGet(
      `/api/reference-data/export-csv?typologies=${demande('TYPOLOGIES')}&emplacements=${demande('EMPLACEMENTS')}&stands=${demande('STANDS')}&creneaux=${demande('CRENEAUX')}&journeesTypes=${demande('JOURNEES_TYPES')}&animateurs=${demande('ANIMATEURS')}`,
      'referentiels-csv.zip',
      'application/zip',
    );
  }
}
