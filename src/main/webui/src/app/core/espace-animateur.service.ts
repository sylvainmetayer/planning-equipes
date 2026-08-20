// State of the espace animateur (issue #165): the animateur's planning and
// demandes, keyed by the access token carried in the URL. Read-only for the
// planning; the only writes are submitting or withdrawing demandes.

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { ApiService, toError } from './api.service';
import { DemandeEchangeView, EspaceAnimateurView, NouvelleDemandeEchange, PosteAnimateurView } from './models';

@Injectable({ providedIn: 'root' })
export class EspaceAnimateurService {
  private readonly api = inject(ApiService);

  /** Token of the espace currently displayed; set by the shell from the URL. */
  readonly jeton = signal<string | null>(null);
  readonly vue = signal<EspaceAnimateurView | null>(null);
  readonly demandes = signal<DemandeEchangeView[]>([]);
  /** Demandes targeting ME — awaiting my agreement before the admin sees them, plus history. */
  readonly demandesRecues = signal<DemandeEchangeView[]>([]);
  readonly chargement = signal(false);
  /** Message of the load failure, `null` while everything is fine. */
  readonly erreur = signal<string | null>(null);
  /**
   * True when the token is valid but no session is open (401): the interface
   * then offers the e-mail code screen instead of the espace.
   */
  readonly authRequise = signal(false);

  /** Loads (or reloads) the whole espace for one token. */
  async charger(jeton: string): Promise<void> {
    this.jeton.set(jeton);
    this.chargement.set(true);
    this.erreur.set(null);
    this.authRequise.set(false);
    try {
      const [vue, demandes, recues] = await Promise.all([
        this.api.getPreservingHttpError<EspaceAnimateurView>(`/api/espace-animateur/${jeton}`),
        this.api.getPreservingHttpError<DemandeEchangeView[]>(`/api/espace-animateur/${jeton}/demandes`),
        this.api.getPreservingHttpError<DemandeEchangeView[]>(`/api/espace-animateur/${jeton}/demandes-recues`)
      ]);
      this.vue.set(vue);
      this.demandes.set(demandes);
      this.demandesRecues.set(recues);
    } catch (error) {
      this.vue.set(null);
      this.demandes.set([]);
      this.demandesRecues.set([]);
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this.authRequise.set(true);
      } else {
        this.erreur.set(toError(error).message);
      }
    } finally {
      this.chargement.set(false);
    }
  }

  /**
   * Asks the server to mail a fresh access code; returns the masked address
   * it went to, for the confirmation line under the input.
   */
  async demanderCode(): Promise<string> {
    const jeton = this.jetonRequis();
    const reponse = await this.api.post<{ emailMasque: string }>(`/api/espace-animateur/${jeton}/code`, null);
    return reponse.emailMasque;
  }

  /** Exchanges the received code for the session cookie, then loads the espace. */
  async validerCode(code: string): Promise<void> {
    const jeton = this.jetonRequis();
    await this.api.post<void>(`/api/espace-animateur/${jeton}/session`, { code });
    await this.charger(jeton);
  }

  /**
   * Submits a batch of demandes and returns them as stored — including the
   * hard-constraint prevalidation verdicts the animateur must be shown.
   */
  /** Agrees with a demande targeting me: it enters the admin queue, both sides now OK. */
  async accorderRecue(demandeId: string): Promise<void> {
    const jeton = this.jetonRequis();
    const demande = await this.api.post<DemandeEchangeView>(
      `/api/espace-animateur/${jeton}/demandes-recues/${demandeId}/accord`, null);
    this.demandesRecues.set(this.demandesRecues().map((d) => (d.id === demandeId ? demande : d)));
  }

  /** Declines a demande targeting me: terminal, the demandeur is told. */
  async declinerRecue(demandeId: string): Promise<void> {
    const jeton = this.jetonRequis();
    const demande = await this.api.post<DemandeEchangeView>(
      `/api/espace-animateur/${jeton}/demandes-recues/${demandeId}/refus`, null);
    this.demandesRecues.set(this.demandesRecues().map((d) => (d.id === demandeId ? demande : d)));
  }

  /** A colleague's seats, for the « créneau souhaité en échange » picker of a directed exchange. */
  async postesCollegue(collegueId: string): Promise<PosteAnimateurView[]> {
    return this.api.get<PosteAnimateurView[]>(
      `/api/espace-animateur/${this.jeton()}/collegues/${encodeURIComponent(collegueId)}/postes`);
  }

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
      await this.api.getPreservingHttpError<DemandeEchangeView[]>(`/api/espace-animateur/${jeton}/demandes`)
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
