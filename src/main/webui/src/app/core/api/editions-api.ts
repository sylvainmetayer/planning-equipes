// The `/api/editions/*` reads and writes; `edition.store.ts` holds the state.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  ActivationPreview,
  CoherenceReport,
  Edition,
  EditionDelta,
  EditionSituation,
  EtatEdition,
  EtatGel,
  FreezeFamily,
} from '../models';

@Injectable({ providedIn: 'root' })
export class EditionsApi {
  private readonly api = inject(ApiService);

  /** Every edition, for the switcher and the Éditions page. */
  list(): Promise<Edition[]> {
    return this.api.get<Edition[]>('/api/editions');
  }

  /** The edition this browser works on — refused when the stored choice names none, or a deleted one. */
  current(): Promise<Edition> {
    return this.api.get<Edition>('/api/editions/courant');
  }

  /** The checklist of the current edition's cycle, one call for the home screen. */
  etat(): Promise<EtatEdition> {
    return this.api.get<EtatEdition>('/api/editions/courant/etat');
  }

  /** The coherence checklist of the referential, line by line — read when its panel is unfolded. */
  coherence(): Promise<CoherenceReport> {
    return this.api.get<CoherenceReport>('/api/editions/courant/coherence');
  }

  /** The freeze of the referential, family by family (ADR 0052). */
  gel(): Promise<EtatGel[]> {
    return this.api.get<EtatGel[]>('/api/editions/courant/gel');
  }

  /** Freezes the family; freezing twice keeps the first date. */
  freeze(famille: FreezeFamily): Promise<EtatGel> {
    return this.api.put<EtatGel>(`/api/editions/courant/gel/${encodeURIComponent(famille)}`, {});
  }

  /** Lifts the freeze of the family. */
  lift(famille: FreezeFamily): Promise<void> {
    return this.api.delete(`/api/editions/courant/gel/${encodeURIComponent(famille)}`);
  }

  /**
   * A new edition, empty or duplicated from `source`. It is born inactive:
   * it reaches nobody until it is activated.
   *
   * `avecAnimateurs` is only read on a duplication: `false` leaves the people
   * behind (issue #90), which is what the year-template case wants — preparing
   * 2027 from 2026 has no business copying the names, birth dates and e-mail
   * addresses of people who have not signed up again.
   *
   * Only the name is sent: the server draws the new edition's id.
   */
  create(nom: string, source: string | null, avecAnimateurs = true): Promise<Edition> {
    if (!source) {
      return this.api.post<Edition>('/api/editions', { nom });
    }
    const url = `/api/editions/${encodeURIComponent(source)}/dupliquer?avecAnimateurs=${avecAnimateurs}`;
    return this.api.post<Edition>(url, { nom });
  }

  rename(editionId: string, nom: string): Promise<unknown> {
    return this.api.put(`/api/editions/${encodeURIComponent(editionId)}`, { nom });
  }

  /** What activating `editionId` would close in the edition active today. */
  activationPreview(editionId: string): Promise<ActivationPreview> {
    return this.api.get<ActivationPreview>(
      `/api/editions/${encodeURIComponent(editionId)}/activation`,
    );
  }

  /** Makes `editionId` the active edition, and the former one inactive, in one gesture. */
  activate(editionId: string): Promise<Edition> {
    return this.api.put<Edition>(`/api/editions/${encodeURIComponent(editionId)}/active`, {});
  }

  /** Leaves no edition active: nothing reaches outside any more. */
  deactivate(editionId: string): Promise<void> {
    return this.api.delete(`/api/editions/${encodeURIComponent(editionId)}/active`);
  }

  /** What the editions' state asks of the organiser today, for the shell's banner. */
  situations(): Promise<EditionSituation[]> {
    return this.api.get<EditionSituation[]>('/api/editions/situations');
  }

  delete(editionId: string): Promise<void> {
    return this.api.delete(`/api/editions/${encodeURIComponent(editionId)}`);
  }

  /**
   * What changed in the referential from edition `reference` to edition
   * `cible` — differences only, rows matched by code, name or e-mail, never
   * by id. Animateurs are named: this admin screen only.
   */
  delta(reference: string, cible: string): Promise<EditionDelta> {
    return this.api.get<EditionDelta>(
      `/api/editions/${encodeURIComponent(reference)}/delta/${encodeURIComponent(cible)}`,
    );
  }

  /** The same delta as a CSV, named by the server: ids and field names, nobody's name. */
  exportDeltaCsv(reference: string, cible: string): Promise<string> {
    return this.api.downloadGetNamedByServer(
      `/api/editions/${encodeURIComponent(reference)}/delta/${encodeURIComponent(cible)}/export.csv`,
      'delta-editions.csv',
      'text/csv',
    );
  }
}
