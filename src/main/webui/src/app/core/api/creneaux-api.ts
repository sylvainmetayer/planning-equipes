// The grid of créneaux and what shapes it: `/api/creneaux/*`, `/api/decoupage/*`
// and `/api/parametres-decoupage` — see planning-api.ts for why the paths live
// here and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  Creneau,
  DerivationRequest,
  DiagnosticGrille,
  ModeGrilleCreneaux,
  ParametresDecoupage,
  RapportDerivation,
  RapportGrille,
  RapportRecurrence,
  RegleRecurrence,
} from '../models';

@Injectable({ providedIn: 'root' })
export class CreneauxApi {
  private readonly api = inject(ApiService);

  /** What the data says about the grid's mode, whatever the declaration says. */
  diagnostic(): Promise<DiagnosticGrille> {
    return this.api.get<DiagnosticGrille>('/api/creneaux/diagnostic');
  }

  /** The verdict on the grid: anomalies, stand openings, feasibility. */
  control(): Promise<RapportGrille> {
    return this.api.get<RapportGrille>('/api/creneaux/controle');
  }

  previewDerivation(
    mode: ModeGrilleCreneaux,
    request: DerivationRequest,
  ): Promise<RapportDerivation> {
    return this.api.post<RapportDerivation>(
      `/api/creneaux/derivation/apercu?mode=${mode}`,
      request,
    );
  }

  /** Writes the créneaux derived from the stands' opening hours. */
  derive(mode: ModeGrilleCreneaux, request: DerivationRequest): Promise<RapportDerivation> {
    return this.api.post<RapportDerivation>(`/api/creneaux/derivation?mode=${mode}`, request);
  }

  previewRecurrence(mode: ModeGrilleCreneaux, regle: RegleRecurrence): Promise<RapportRecurrence> {
    return this.api.post<RapportRecurrence>(`/api/creneaux/recurrence/apercu?mode=${mode}`, regle);
  }

  /** Writes the créneaux a recurring rule produces. */
  createRecurrence(mode: ModeGrilleCreneaux, regle: RegleRecurrence): Promise<RapportRecurrence> {
    return this.api.post<RapportRecurrence>(`/api/creneaux/recurrence?mode=${mode}`, regle);
  }

  slicingParameters(): Promise<ParametresDecoupage> {
    return this.api.get<ParametresDecoupage>('/api/parametres-decoupage');
  }

  saveSlicingParameters(parametres: ParametresDecoupage): Promise<ParametresDecoupage> {
    return this.api.put<ParametresDecoupage>('/api/parametres-decoupage', parametres);
  }

  /**
   * The grid's declared mode alone, on its own endpoint: sending the whole
   * settings object would let a stale Paramètres tab revert this choice.
   */
  setGridMode(modeGrille: ModeGrilleCreneaux): Promise<ParametresDecoupage> {
    return this.api.put<ParametresDecoupage>('/api/parametres-decoupage/mode-grille', {
      modeGrille,
    });
  }

  /** The vacations the slicing would produce, without writing them. */
  previewSlicing(): Promise<Creneau[]> {
    return this.api.get<Creneau[]>('/api/decoupage/preview');
  }

  /** Replaces the edition's créneaux by the sliced vacations, and erases the persisted plan with them. */
  generateSlicing(): Promise<void> {
    return this.api.post<void>('/api/decoupage/generer', {});
  }
}
