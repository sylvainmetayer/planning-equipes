// The edition's consignes (issue #4): a band every stand is shut on for one
// date, the stands reopened in compensation, and the presets they are made
// from — `/api/consignes/*`. See planning-api.ts for why the paths live here
// and not in the pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from '../api.service';
import {
  ApercuConsigneJour,
  ApercuLeveeConsigne,
  DemandeConsigne,
  DemandePreselectionConsigne,
  EtatConsignes,
  PreselectionConsigne,
  PrereglageConsigne,
} from '../models';

/** What a preset write carries: the preset without its id and its stamp. */
export type PrereglageConsigneSaisi = Omit<PrereglageConsigne, 'id' | 'creeLe' | 'modifieLe'>;

@Injectable({ providedIn: 'root' })
export class ConsignesApi {
  private readonly api = inject(ApiService);

  /** The consignes, the presets, the indicators and the server's today, in one read. */
  etat(): Promise<EtatConsignes> {
    return this.api.get<EtatConsignes>('/api/consignes');
  }

  /** What the band takes from each stand of one date, and which are proposed ticked. Writes nothing. */
  preselection(demande: DemandePreselectionConsigne): Promise<PreselectionConsigne> {
    return this.api.post<PreselectionConsigne>('/api/consignes/preselection', demande);
  }

  /** What laying the consigne down would do, date by date. Writes nothing. */
  apercu(demande: DemandeConsigne): Promise<ApercuConsigneJour[]> {
    return this.api.post<ApercuConsigneJour[]>('/api/consignes/apercu', demande);
  }

  /** Lays the consigne down on every date of the request — replacing the one a date already carries. */
  poser(demande: DemandeConsigne): Promise<ApercuConsigneJour[]> {
    return this.api.post<ApercuConsigneJour[]>('/api/consignes', demande);
  }

  /** What lifting would do. Writes nothing. */
  apercuLevee(dates: string[]): Promise<ApercuLeveeConsigne[]> {
    return this.api.post<ApercuLeveeConsigne[]>('/api/consignes/levee/apercu', { dates });
  }

  /** Lifts the consigne of the given dates — days to come only. */
  lever(dates: string[]): Promise<void> {
    return this.api.post<void>('/api/consignes/levee', { dates });
  }

  prereglages(): Promise<PrereglageConsigne[]> {
    return this.api.get<PrereglageConsigne[]>('/api/consignes/prereglages');
  }

  createPrereglage(prereglage: PrereglageConsigneSaisi): Promise<PrereglageConsigne> {
    return this.api.post<PrereglageConsigne>('/api/consignes/prereglages', prereglage);
  }

  updatePrereglage(id: string, prereglage: PrereglageConsigneSaisi): Promise<PrereglageConsigne> {
    return this.api.put<PrereglageConsigne>(
      `/api/consignes/prereglages/${encodeURIComponent(id)}`,
      prereglage,
    );
  }

  deletePrereglage(id: string): Promise<void> {
    return this.api.delete(`/api/consignes/prereglages/${encodeURIComponent(id)}`);
  }
}
