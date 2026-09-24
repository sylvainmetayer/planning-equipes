// State of the espace animateur (issue #165): the animateur's planning and
// demandes, keyed by the access token carried in the URL. Read-only for the
// planning; the only writes are submitting or withdrawing demandes.

import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { ApiService, toError } from './api.service';
import {
  CarpoolEspaceView,
  DeclarationEspaceView,
  AccuseReception,
  DemandeEchangeView,
  EspaceAnimateurView,
  NewCarpoolRequest,
  NouvelleDeclaration,
  NouvelleDemandeEchange,
  PosteAnimateurView,
  SuggestionsEchangeView,
} from './models';

@Injectable({ providedIn: 'root' })
export class EspaceAnimateurService {
  private readonly api = inject(ApiService);

  /** Token of the espace currently displayed; set by the shell from the URL. */
  private readonly _jeton = signal<string | null>(null);
  readonly jeton = this._jeton.asReadonly();
  private readonly _view = signal<EspaceAnimateurView | null>(null);
  readonly view = this._view.asReadonly();
  private readonly _demandes = signal<DemandeEchangeView[]>([]);
  readonly demandes = this._demandes.asReadonly();
  /** Demandes targeting ME — awaiting my agreement before the admin sees them, plus history. */
  private readonly _demandesRecues = signal<DemandeEchangeView[]>([]);
  readonly demandesRecues = this._demandesRecues.asReadonly();
  private readonly _chargement = signal(false);
  readonly chargement = this._chargement.asReadonly();
  /** Message of the load failure, `null` while everything is fine. */
  private readonly _erreur = signal<string | null>(null);
  readonly erreur = this._erreur.asReadonly();
  /**
   * True when the espace answers 401: no Keycloak session opens this token's
   * fiche (none at all, or the wrong account), or Keycloak is off on this
   * deployment. The shell then renders its access screen instead.
   */
  private readonly _authRequise = signal(false);
  readonly authRequise = this._authRequise.asReadonly();

  /** Loads (or reloads) the whole espace for one token. */
  async charger(jeton: string): Promise<void> {
    this._jeton.set(jeton);
    this._chargement.set(true);
    this._erreur.set(null);
    this._authRequise.set(false);
    try {
      const [view, demandes, recues] = await Promise.all([
        this.api.getPreservingHttpError<EspaceAnimateurView>(`/api/espace-animateur/${jeton}`),
        this.api.getPreservingHttpError<DemandeEchangeView[]>(
          `/api/espace-animateur/${jeton}/demandes`,
        ),
        this.api.getPreservingHttpError<DemandeEchangeView[]>(
          `/api/espace-animateur/${jeton}/demandes-recues`,
        ),
      ]);
      this._view.set(view);
      this._demandes.set(demandes);
      this._demandesRecues.set(recues);
    } catch (error) {
      this._view.set(null);
      this._demandes.set([]);
      this._demandesRecues.set([]);
      if (error instanceof HttpErrorResponse && error.status === 401) {
        this._authRequise.set(true);
      } else {
        this._erreur.set(toError(error).message);
      }
    } finally {
      this._chargement.set(false);
    }
  }

  /**
   * Submits a batch of demandes and returns them as stored — including the
   * hard-constraint prevalidation verdicts the animateur must be shown.
   */
  /** Agrees with a demande targeting me: it enters the admin queue, both sides now OK. */
  async accorderRecue(demandeId: string): Promise<void> {
    const jeton = this.requireJeton();
    const demande = await this.api.post<DemandeEchangeView>(
      `/api/espace-animateur/${jeton}/demandes-recues/${demandeId}/accord`,
      null,
    );
    this._demandesRecues.set(this.demandesRecues().map((d) => (d.id === demandeId ? demande : d)));
  }

  /** Declines a demande targeting me: terminal, the demandeur is told. */
  async declinerRecue(demandeId: string): Promise<void> {
    const jeton = this.requireJeton();
    const demande = await this.api.post<DemandeEchangeView>(
      `/api/espace-animateur/${jeton}/demandes-recues/${demandeId}/refus`,
      null,
    );
    this._demandesRecues.set(this.demandesRecues().map((d) => (d.id === demandeId ? demande : d)));
  }

  /**
   * « J'ai lu et je serai là » (issue #293). Refreshes the loaded view in
   * place rather than reloading the whole espace: the answer carries the new
   * state, and nothing else on the page can have moved because of the click.
   */
  async confirmerPlanning(): Promise<void> {
    const jeton = this.requireJeton();
    const accuse = await this.api.post<AccuseReception>(
      `/api/espace-animateur/${jeton}/confirmation`,
      null,
    );
    const view = this.view();
    if (view) {
      this._view.set({ ...view, statutConfirmation: accuse.statut, confirmeLe: accuse.confirmeLe });
    }
  }

  /**
   * Revokes the calendar subscription URL and mints a new one (issue #324).
   * Refreshes the loaded view in place: the answer carries the new token, and
   * nothing else on the page can have moved because of the click.
   */
  async regenererAbonnement(): Promise<void> {
    const jeton = this.requireJeton();
    const reponse = await this.api.post<{ abonnementToken: string }>(
      `/api/espace-animateur/${jeton}/abonnement`,
      null,
    );
    const view = this.view();
    if (view) {
      this._view.set({ ...view, abonnementToken: reponse.abonnementToken });
    }
  }

  /** A colleague's seats, for the « créneau souhaité en échange » picker of a directed exchange. */
  async postesCollegue(collegueId: string): Promise<PosteAnimateurView[]> {
    return this.api.get<PosteAnimateurView[]>(
      `/api/espace-animateur/${this.jeton()}/collegues/${encodeURIComponent(collegueId)}/postes`,
    );
  }

  /**
   * Colleagues this seat could really be traded with — the « qui peut me
   * remplacer ? » button, for when the animateur wants rid of a slot and has
   * nobody in mind. Read-only: it creates no demande.
   */
  async suggestionsEchange(creneauId: number, standId: string): Promise<SuggestionsEchangeView> {
    const jeton = this.requireJeton();
    return this.api.get<SuggestionsEchangeView>(
      `/api/espace-animateur/${jeton}/suggestions-echange` +
        `?creneauId=${creneauId}&standId=${encodeURIComponent(standId)}`,
    );
  }

  async soumettre(nouvelles: NouvelleDemandeEchange[]): Promise<DemandeEchangeView[]> {
    const jeton = this.requireJeton();
    const soumises = await this.api.post<DemandeEchangeView[]>(
      `/api/espace-animateur/${jeton}/demandes`,
      nouvelles,
    );
    this._demandes.set([...soumises, ...this.demandes()]);
    return soumises;
  }

  /** Withdraws one still-pending demande, then refreshes the list. */
  async annuler(demandeId: string): Promise<void> {
    const jeton = this.requireJeton();
    await this.api.post<void>(
      `/api/espace-animateur/${jeton}/demandes/${demandeId}/annulation`,
      null,
    );
    this._demandes.set(
      await this.api.getPreservingHttpError<DemandeEchangeView[]>(
        `/api/espace-animateur/${jeton}/demandes`,
      ),
    );
  }

  /* ----- Declaration of availability (issue #291): the espace's only write ----- */

  /** `null` until the declaration tab has been opened once. */
  private readonly _declaration = signal<DeclarationEspaceView | null>(null);
  readonly declaration = this._declaration.asReadonly();

  /** Loads (or reloads) the declaration tab. Kept out of `charger`: three
   * requests already fire on entering the espace, and most visits never open
   * this tab. */
  async chargerDeclaration(): Promise<void> {
    const jeton = this.requireJeton();
    this._declaration.set(
      await this.api.get<DeclarationEspaceView>(`/api/espace-animateur/${jeton}/disponibilites`),
    );
  }

  /**
   * Sends what I declare. It replaces whatever I had pending rather than
   * queueing behind it, and nothing of it reaches the organisation's data
   * before they apply it.
   */
  async declarer(nouvelle: NouvelleDeclaration): Promise<void> {
    const jeton = this.requireJeton();
    this._declaration.set(
      await this.api.post<DeclarationEspaceView>(
        `/api/espace-animateur/${jeton}/disponibilites`,
        nouvelle,
      ),
    );
  }

  /* ----- Covoiturage: « Je viens avec… », apart from the declaration ----- */

  /** `null` until the Covoiturage tab has been opened once. */
  private readonly _carpool = signal<CarpoolEspaceView | null>(null);
  readonly carpool = this._carpool.asReadonly();

  /** Loads (or reloads) the Covoiturage tab, only when it is opened. */
  async loadCarpool(): Promise<void> {
    const jeton = this.requireJeton();
    this._carpool.set(
      await this.api.get<CarpoolEspaceView>(`/api/espace-animateur/${jeton}/covoiturage`),
    );
  }

  /**
   * Sends my covoiturage request, replacing the pending one; an empty list
   * withdraws it. Nothing of it touches my declaration of availability.
   */
  async requestCarpool(request: NewCarpoolRequest): Promise<void> {
    const jeton = this.requireJeton();
    this._carpool.set(
      await this.api.post<CarpoolEspaceView>(`/api/espace-animateur/${jeton}/covoiturage`, request),
    );
  }

  private requireJeton(): string {
    const jeton = this.jeton();
    if (!jeton) {
      throw new Error('Espace animateur non chargé');
    }
    return jeton;
  }
}
