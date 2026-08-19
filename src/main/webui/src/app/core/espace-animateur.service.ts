// State of the espace animateur (issue #165): the animateur's planning and
// demandes, keyed by the access token carried in the URL. Read-only for the
// planning; the only writes are submitting or withdrawing demandes.

import { Injectable, inject, signal } from '@angular/core';
import { ApiService } from './api.service';
import { DemandeEchangeView, EspaceAnimateurView, NouvelleDemandeEchange } from './models';

@Injectable({ providedIn: 'root' })
export class EspaceAnimateurService {
  private readonly api = inject(ApiService);

  /** Token of the espace currently displayed; set by the shell from the URL. */
  readonly jeton = signal<string | null>(null);
  readonly vue = signal<EspaceAnimateurView | null>(null);
  readonly demandes = signal<DemandeEchangeView[]>([]);
  readonly chargement = signal(false);
  /** Message of the load failure, `null` while everything is fine. */
  readonly erreur = signal<string | null>(null);

  /** Loads (or reloads) the whole espace for one token. */
  async charger(jeton: string): Promise<void> {
    this.jeton.set(jeton);
    this.chargement.set(true);
    this.erreur.set(null);
    try {
      const [vue, demandes] = await Promise.all([
        this.api.get<EspaceAnimateurView>(`/api/espace-animateur/${jeton}`),
        this.api.get<DemandeEchangeView[]>(`/api/espace-animateur/${jeton}/demandes`)
      ]);
      this.vue.set(vue);
      this.demandes.set(demandes);
    } catch (error) {
      this.vue.set(null);
      this.demandes.set([]);
      this.erreur.set(error instanceof Error ? error.message : String(error));
    } finally {
      this.chargement.set(false);
    }
  }

  /**
   * Submits a batch of demandes and returns them as stored — including the
   * hard-constraint prevalidation verdicts the animateur must be shown.
   */
  async soumettre(nouvelles: NouvelleDemandeEchange[]): Promise<DemandeEchangeView[]> {
    const jeton = this.jetonRequis();
    const soumises = await this.api.post<DemandeEchangeView[]>(
      `/api/espace-animateur/${jeton}/demandes`,
      nouvelles
    );
    this.demandes.set([...soumises, ...this.demandes()]);
    return soumises;
  }

  /** Withdraws one still-pending demande, then refreshes the list. */
  async annuler(demandeId: string): Promise<void> {
    const jeton = this.jetonRequis();
    await this.api.post<void>(`/api/espace-animateur/${jeton}/demandes/${demandeId}/annulation`, null);
    this.demandes.set(
      await this.api.get<DemandeEchangeView[]>(`/api/espace-animateur/${jeton}/demandes`)
    );
  }

  private jetonRequis(): string {
    const jeton = this.jeton();
    if (!jeton) {
      throw new Error('Espace animateur non chargé');
    }
    return jeton;
  }
}
