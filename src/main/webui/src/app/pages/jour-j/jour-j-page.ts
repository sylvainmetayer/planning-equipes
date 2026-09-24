import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { AffectationExplanationService } from '../../core/affectation-explanation.service';
import { JourJService } from '../../core/jour-j.service';
import {
  AbsenceJourJ,
  AnimateurAffecte,
  ApercuPublication,
  EtatJourJ,
  PosteAPourvoir,
  SuggestionsReparation,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { compareDelta } from '../../shared/affectation-explanation-rules';
import { bandeLabel } from '../../core/consigne-wording';
import { WorkInProgressBanner } from '../../shared/work-in-progress-banner';
import {
  aucuneSuggestion,
  blocageDuPoste,
  chargeRestante,
  dejaDeService,
  libelleCreneau,
  nomDuCandidat,
  plage,
  porteeDesSuggestions,
  rappelPublication,
  resumeDuJour,
} from './jour-j-wording';
import { StatusMessage } from '../../shared/status-message';

/**
 * Mode « jour J » — the first screen of this application written for the day of
 * the event rather than for the weeks before it: somebody did not show up, and
 * their seats have to change hands now.
 *
 * <p>Three sections, in the order the gesture actually goes: who is missing →
 * which seats that opened → who can take one. Nothing navigates away, because
 * the person holding the phone is standing in an aisle with somebody waiting.
 *
 * <p>Two things it deliberately does *not* do. It never starts a solve — every
 * write is the repair assistant's surgical UPDATE. And it never sends a mail:
 * the publication count is shown as a reminder with a link to the page that
 * owns the button, because a "write to 150 people" control one tap away inside
 * an emergency screen gets pressed by accident.
 */
@Component({
  selector: 'app-jour-j-page',
  imports: [
    StatusMessage,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    WorkInProgressBanner,
  ],
  templateUrl: './jour-j-page.html',
  styleUrl: './jour-j-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JourJPage {
  private readonly jourJ = inject(JourJService);
  private readonly reparations = inject(AffectationExplanationService);
  private readonly notifications = inject(NotificationService);
  /**
   * A solve holding the edition refuses this write in 409 — its landing
   * rewrites every seat from the plan it started on: the buttons wait for it.
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly etat = signal<EtatJourJ | null>(null);
  protected readonly apercu = signal<ApercuPublication | null>(null);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal('');

  /** Which animateur's "marquer absent" panel is open. Only ever one at a time. */
  protected readonly candidatAbsence = signal<string | null>(null);
  protected readonly raison = signal('');
  protected readonly enCoursDAbsence = signal(false);

  /** Suggestions already fetched, by poste id — one entry per hole the operator opened. */
  protected readonly suggestionsParPoste = signal<Record<string, SuggestionsReparation>>({});
  /** Poste ids whose suggestion call is in flight, so each card spins on its own. */
  protected readonly rechercheEnCours = signal<string[]>([]);
  /** Poste id being written right now, so only its buttons are disabled. */
  protected readonly affectationEnCours = signal<string | null>(null);

  /**
   * The default banner wording says the data entered here "may still change"
   * and that the solver "may not take it into account". Both are too gentle for
   * this screen: it is the only one under trial that <em>writes</em>, and what
   * it writes lands in the persisted plan straight away. Somebody has to
   * understand, before tapping, that they are changing the plan and that
   * nothing revalidates the whole of it until the next solve.
   */
  protected readonly avertissement = $localize`:@@jourJ.wip.message:Écran en cours de développement, et il agit : marquer un absent écrit de vraies indisponibilités et vide de vrais sièges du planning enregistré. Ses effets ne sont pas encore garantis.`;

  protected readonly resume = computed(() => resumeDuJour(this.etat()));
  /** The day's consigne (issue #4), worded for the banner; empty on an ordinary day. */
  protected readonly consigne = computed(() => {
    const consigne = this.etat()?.consigne ?? null;
    return consigne
      ? { bande: bandeLabel(consigne.fermetureDebut, consigne.fermetureFin), motif: consigne.motif }
      : null;
  });
  protected readonly rappel = computed(() => rappelPublication(this.apercu()));

  /** Pre-labelled rows: the template never calls a function per row. */
  protected readonly deService = computed(() =>
    (this.etat()?.animateursDeService ?? []).map((animateur: AnimateurAffecte) => ({
      ...animateur,
      charge: chargeRestante(animateur),
    })),
  );

  protected readonly creneaux = computed(() =>
    (this.etat()?.creneauxRestants ?? []).map((creneau) => ({
      id: creneau.id,
      libelle: libelleCreneau(creneau),
    })),
  );

  protected readonly trous = computed(() =>
    (this.etat()?.postesAPourvoir ?? []).map((poste: PosteAPourvoir) => ({
      ...poste,
      plage: plage(poste.heureDebut, poste.heureFin),
      blocage: blocageDuPoste(poste),
    })),
  );

  protected readonly absences = computed(() =>
    (this.etat()?.absences ?? []).map((absence: AbsenceJourJ) => ({
      ...absence,
      // Nothing cancellable means no block button: it would call an endpoint
      // whose filter finds nothing and answers "impossible", on a control the
      // screen itself had offered.
      annulable: absence.entrees.some((entree) => entree.annulable),
      entrees: absence.entrees.map((entree) => ({
        ...entree,
        plage: plage(entree.heureDebut, entree.heureFin),
      })),
    })),
  );

  constructor() {
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    this.erreur.set('');
    try {
      this.etat.set(await this.jourJ.etat());
    } catch (error) {
      this.erreur.set(messageDe(error));
    } finally {
      this.chargement.set(false);
    }
    // The publication count is a reminder, not part of the day's state: a
    // failure to read it must not blank the screen the operator came for.
    try {
      this.apercu.set(await this.jourJ.apercuPublication());
    } catch {
      this.apercu.set(null);
    }
  }

  /* ------------------------------- Absence ------------------------------- */

  protected ouvrirAbsence(animateurId: string): void {
    this.candidatAbsence.set(animateurId);
    this.raison.set('');
  }

  protected fermerAbsence(): void {
    this.candidatAbsence.set(null);
    this.raison.set('');
  }

  /**
   * Writes the absence and goes straight to the holes it opened: the freed
   * seats come back in the answer, so their suggestions are fetched one after
   * the other rather than all at once — each call costs the server twenty full
   * analyses of the plan, and firing five in parallel would make the screen
   * slower, not faster.
   */
  protected async marquerAbsent(animateurId: string): Promise<void> {
    this.enCoursDAbsence.set(true);
    try {
      const marquee = await this.jourJ.marquerAbsent(animateurId, this.raison());
      this.fermerAbsence();
      this.notifications.notify({
        title: $localize`:@@jourJ.absence.faite:${marquee.nomAffiche}:nom: est marqué absent`,
        message: $localize`:@@jourJ.absence.detail:${marquee.entrees.length}:creneaux: créneau(x) indisponibles, ${marquee.postesLiberes.length}:postes: poste(s) libéré(s).`,
        variant: 'success',
      });
      await this.recharger();
      for (const poste of marquee.postesLiberes) {
        await this.chercherRemplacants(poste.posteId);
      }
    } catch (error) {
      // A contradiction with an existing exception, or a locked seat: the
      // server names both sides, so the message is shown whole and kept open.
      this.notifications.notify({
        title: $localize`:@@jourJ.absence.refusee:Absence refusée`,
        message: messageDe(error),
        variant: 'error',
        timeout: 0,
      });
    } finally {
      this.enCoursDAbsence.set(false);
    }
  }

  protected async annulerAbsence(animateurId: string, creneauId?: number): Promise<void> {
    try {
      await this.jourJ.annulerAbsence(animateurId, undefined, creneauId);
      await this.recharger();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@jourJ.annulation.echec:Annulation impossible`,
        message: messageDe(error),
        variant: 'error',
      });
    }
  }

  /* ----------------------------- Remplacement ---------------------------- */

  protected enRecherche(posteId: string): boolean {
    return this.rechercheEnCours().includes(posteId);
  }

  protected suggestionsDe(posteId: string): SuggestionsReparation | null {
    return this.suggestionsParPoste()[posteId] ?? null;
  }

  protected portee(posteId: string): string {
    return porteeDesSuggestions(this.suggestionsDe(posteId));
  }

  protected vide(posteId: string): boolean {
    return aucuneSuggestion(this.suggestionsDe(posteId));
  }

  /** The candidates of one hole, already named and ranked by the server. */
  protected candidats(posteId: string) {
    const suggestions = this.suggestionsDe(posteId);
    return (suggestions?.suggestions ?? []).map((suggestion) => ({
      animateurId: suggestion.animateurId,
      nom: nomDuCandidat(this.etat(), suggestion.animateurId),
      ameliore: compareDelta(suggestion.delta) === 'better',
      // Worth saying on the button: taking this seat adds to a day they are
      // already working, rather than filling an idle one.
      dejaDeService: dejaDeService(this.etat(), suggestion.animateurId),
    }));
  }

  protected async chercherRemplacants(posteId: string): Promise<void> {
    this.rechercheEnCours.update((liste) => [...liste, posteId]);
    try {
      const suggestions = await this.jourJ.suggestions(posteId);
      this.suggestionsParPoste.update((par) => ({ ...par, [posteId]: suggestions }));
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@jourJ.suggestions.echec:Recherche impossible`,
        message: messageDe(error),
        variant: 'error',
      });
    } finally {
      this.rechercheEnCours.update((liste) =>
        liste.filter((identifiant) => identifiant !== posteId),
      );
    }
  }

  /**
   * One tap, one seat. Reuses the repair assistant's own write — the surgical
   * UPDATE that starts no solve and touches nothing else in the plan.
   */
  protected async affecter(posteId: string, animateurId: string): Promise<void> {
    this.affectationEnCours.set(posteId);
    try {
      await this.reparations.appliquerReparation(posteId, animateurId);
      // Every list on screen was computed against the plan as it was a moment
      // ago. Keeping the others would let the same person be assigned twice on
      // the same hour: the write is a surgical UPDATE that checks locks and
      // nothing else, so a stale list is all it takes to create an overlap this
      // screen would never report. They are dropped, then searched again.
      const aRafraichir = Object.keys(this.suggestionsParPoste()).filter(
        (identifiant) => identifiant !== posteId,
      );
      this.suggestionsParPoste.set({});
      this.notifications.notify({
        title: $localize`:@@jourJ.affectation.faite:Poste pourvu`,
        message: $localize`:@@jourJ.affectation.detail:${nomDuCandidat(this.etat(), animateurId)}:nom: prend ce poste. Le planning publié ne bouge pas tant que vous n'avez pas republié.`,
        variant: 'success',
      });
      await this.recharger();
      const trousRestants = new Set(this.trous().map((trou) => trou.posteId));
      for (const identifiant of aRafraichir) {
        if (trousRestants.has(identifiant)) {
          await this.chercherRemplacants(identifiant);
        }
      }
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@jourJ.affectation.echec:Affectation refusée`,
        message: messageDe(error),
        variant: 'error',
        timeout: 0,
      });
    } finally {
      this.affectationEnCours.set(null);
    }
  }
}

/** The readable half of whatever `ApiService` rejected with. */
function messageDe(error: unknown): string {
  return error instanceof Error && error.message
    ? error.message
    : $localize`:@@jourJ.erreur.inconnue:Erreur inattendue.`;
}
