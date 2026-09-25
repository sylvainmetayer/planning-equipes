// The end-of-event archive: `/api/exports/archive-evenement*`. See
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ArchiveAvailability, ArchivePart } from '../models';

/** Layout of the individual PDFs, as the Publication screen names them. */
export type ArchiveFormat = 'livret' | 'feuille';

@Injectable({ providedIn: 'root' })
export class ArchiveEvenementApi {
  private readonly api = inject(ApiService);

  /** Which parts would come out empty right now. */
  availability(): Promise<ArchiveAvailability> {
    return this.api.get<ArchiveAvailability>('/api/exports/archive-evenement/disponibilite');
  }

  /**
   * Downloads the archive of the chosen parts, under the name the server
   * gives it (`archive-<edition>-<date>.zip`).
   */
  telecharger(parts: readonly ArchivePart[], format: ArchiveFormat): Promise<string> {
    const demande = (part: ArchivePart) => parts.includes(part);
    // Every flag spelled out, always: that is what lets `check-api-contract`
    // confront them with the contract.
    return this.api.downloadGetNamedByServer(
      `/api/exports/archive-evenement?pdfGlobal=${demande('pdfGlobal')}&equite=${demande('equite')}&heures=${demande('heures')}&referentiels=${demande('referentiels')}&scenario=${demande('scenario')}&publication=${demande('publication')}&individuels=${demande('individuels')}&format=${format}`,
      'archive-evenement.zip',
      'application/zip',
    );
  }
}
