// The referential CSV export: `/api/reference-data/export-csv*`. See
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { CibleExportCsv, VolumesExportCsv } from '../models';

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
   * <p>The four flags travel in the query string rather than a body: a
   * download is a navigation, and a `GET` is what a browser can save.</p>
   */
  telecharger(cibles: readonly CibleExportCsv[]): Promise<string> {
    const demande = (cible: CibleExportCsv) => cibles.includes(cible);
    // Les quatre drapeaux sont écrits en clair, toujours les quatre : c'est
    // ainsi que `check-api-contract` peut les confronter au contrat, là où une
    // requête assemblée par `URLSearchParams` ne lui dit plus rien.
    return this.api.downloadGet(
      `/api/reference-data/export-csv?typologies=${demande('TYPOLOGIES')}&emplacements=${demande('EMPLACEMENTS')}&stands=${demande('STANDS')}&animateurs=${demande('ANIMATEURS')}`,
      'referentiels-csv.zip',
      'application/zip',
    );
  }
}
