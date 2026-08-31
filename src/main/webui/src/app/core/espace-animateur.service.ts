// State of the espace animateur (issue #165): the animateur's planning and
// demandes, keyed by the access token carried in the URL. Read-only for the
// planning; the only writes are submitting or withdrawing demandes.

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { ApiService, toError } from './api.service';
import {
  DeclarationEspaceView,
  AccuseReception,
  DemandeEchangeView,
  EspaceAnimateurView,
  NouvelleDeclaration,
  NouvelleDemandeEchange,
  PosteAnimateurView,
  SuggestionsEchangeView
} from './models';

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

  /**
   * « J'ai lu et je serai là » (issue #293). Refreshes the loaded view in
   * place rather than reloading the whole espace: the answer carries the new
   * state, and nothing else on the page can have moved because of the click.
   */
  async confirmerPlanning(): Promise<void> {
    const jeton = this.jetonRequis();
    const accuse = await this.api.post<AccuseReception>(`/api/espace-animateur/${jeton}/confirmation`, null);
    const vue = this.vue();
    if (vue) {
      this.vue.set({ ...vue, statutConfirmation: accuse.statut, confirmeLe: accuse.confirmeLe });
    }
  }

  /**
   * Revokes the calendar subscription URL and mints a new one (issue #324).
   * Refreshes the loaded view in place: the answer carries the new token, and
   * nothing else on the page can have moved because of the click.
   */
  async regenererAbonnement(): Promise<void> {
    const jeton = this.jetonRequis();
    const reponse = await this.api.post<{ abonnementToken: string }>(
      `/api/espace-animateur/${jeton}/abonnement`, null);
    const vue = this.vue();
    if (vue) {
      this.vue.set({ ...vue, abonnementToken: reponse.abonnementToken });
    }
  }

  /** A colleague's seats, for the « créneau souhaité en échange » picker of a directed exchange. */
  async postesCollegue(collegueId: string): Promise<PosteAnimateurView[]> {
    return this.api.get<PosteAnimateurView[]>(
      `/api/espace-animateur/${this.jeton()}/collegues/${encodeURIComponent(collegueId)}/postes`);
  }

  /**
   * Colleagues this seat could really be traded with — the « qui peut me
   * remplacer ? » button, for when the animateur wants rid of a slot and has
   * nobody in mind. Read-only: it creates no demande.
   */
  async suggestionsEchange(creneauId: number, standId: string): Promise<SuggestionsEchangeView> {
    const jeton = this.jetonRequis();
    return this.api.get<SuggestionsEchangeView>(
      `/api/espace-animateur/${jeton}/suggestions-echange`
        + `?creneauId=${creneauId}&standId=${encodeURIComponent(standId)}`);
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

  /* ----- Declaration of availability (issue #291): the espace's only write ----- */

  /** `null` until the declaration tab has been opened once. */
  readonly declaration = signal<DeclarationEspaceView | null>(null);

  /** Loads (or reloads) the declaration tab. Kept out of `charger`: three
   * requests already fire on entering the espace, and most visits never open
   * this tab. */
  async chargerDeclaration(): Promise<void> {
    const jeton = this.jetonRequis();
    this.declaration.set(
      await this.api.get<DeclarationEspaceView>(`/api/espace-animateur/${jeton}/disponibilites`)
    );
  }

  /**
   * Sends what I declare. It replaces whatever I had pending rather than
   * queueing behind it, and nothing of it reaches the organisation's data
   * before they apply it.
   */
  async declarer(nouvelle: NouvelleDeclaration): Promise<void> {
    const jeton = this.jetonRequis();
    this.declaration.set(
      await this.api.post<DeclarationEspaceView>(`/api/espace-animateur/${jeton}/disponibilites`, nouvelle)
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
