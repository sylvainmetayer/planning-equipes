// The `/api/constraints/*`, `/api/parametres-legaux` and `/api/parametres-qualite` endpoints — see
// planning-api.ts for why the paths live here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import { ConstraintsView, ParametresLegaux, ParametresQualite } from '../models';

@Injectable({ providedIn: 'root' })
export class ConstraintsApi {
  private readonly api = inject(ApiService);

  /** The catalogue, with each rule's state and weight, and the last diagnostic when one ran. */
  catalogue(): Promise<ConstraintsView> {
    return this.api.get<ConstraintsView>('/api/constraints');
  }

  /** Runs the diagnostic on the persisted planning and returns the catalogue with its findings. */
  diagnose(): Promise<ConstraintsView> {
    return this.api.post<ConstraintsView>('/api/constraints/diagnostic', {});
  }

  setActive(name: string, actif: boolean): Promise<{ actif: boolean }> {
    return this.api.put<{ actif: boolean }>(`/api/constraints/${encodeURIComponent(name)}`, {
      actif,
    });
  }

  /** The weight as the server kept it — it clamps, and the page shows what was stored. */
  setWeight(name: string, poids: number): Promise<{ poids: number }> {
    return this.api.put<{ poids: number }>(`/api/constraints/${encodeURIComponent(name)}/poids`, {
      poids,
    });
  }

  legalParameters(): Promise<ParametresLegaux> {
    return this.api.get<ParametresLegaux>('/api/parametres-legaux');
  }

  saveLegalParameters(parametres: ParametresLegaux): Promise<ParametresLegaux> {
    return this.api.put<ParametresLegaux>('/api/parametres-legaux', parametres);
  }

  qualityParameters(): Promise<ParametresQualite> {
    return this.api.get<ParametresQualite>('/api/parametres-qualite');
  }

  saveQualityParameters(parametres: ParametresQualite): Promise<ParametresQualite> {
    return this.api.put<ParametresQualite>('/api/parametres-qualite', parametres);
  }
}
