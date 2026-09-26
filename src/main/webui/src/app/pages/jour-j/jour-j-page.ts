import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
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
  EtatJourJ,
  PosteAPourvoir,
  SuggestionsReparation,
  SignalementJourJ,
} from '../../core/models';
import { motifLabel } from '../../core/signalement-wording';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { compareDelta } from '../../shared/affectation-explanation-rules';
import { bandeLabel } from '../../core/consigne-wording';
import { ConfirmService } from '../../shared/confirm-dialog';
import {
  alerteLibelle,
  aucuneSuggestion,
  blocageDuPoste,
  chargeRestante,
  searchPeople,
  dayCounters,
  dejaDeService,
  dayHeader,
  heureDe,
  nomDuCandidat,
  nonPublieLibelle,
  plage,
  porteeDesSuggestions,
  prevenirLibelle,
  resumeDuJour,
} from './jour-j-wording';
import { StatusMessage } from '../../shared/status-message';
import { AujourdhuiTv } from './aujourdhui-tv';

/** A hole of the day, labelled once: the template never calls a function per row. */
interface Trou extends PosteAPourvoir {
  plage: string;
  blocage: string;
}

/**
 * « Aujourd'hui » — the hub of the event day (formerly « Mode jour J »):
 * somebody did not show up, and their seats have to change hands now.
 *
 * <p>Read top down as the gesture goes: the day at a glance and a search to
 * find anybody among the roster → what is new since this morning (the absences
 * and the seats they opened, the reports from the espaces, the wall display's
 * alerts) → the holes everybody already knew, faded. A seat of the timeslot
 * under way is repaired too: the server splits it at « now » and the
 * replacement covers the rest of it (ADR 0066).
 *
 * <p>It never starts a solve — every write is the repair assistant's surgical
 * UPDATE. The one mail it sends is « Prévenir les N personnes », the targeted
 * publication of the Diffuser screen, to the people whose schedule moved and to
 * nobody else, after a confirmation.
 */
@Component({
  selector: 'app-jour-j-page',
  imports: [
    AujourdhuiTv,
    StatusMessage,
    FormsModule,
    NgTemplateOutlet,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
  ],
  templateUrl: './jour-j-page.html',
  styleUrl: './jour-j-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JourJPage implements OnInit {
  private readonly jourJ = inject(JourJService);
  private readonly reparations = inject(AffectationExplanationService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  /**
   * A solve holding the edition refuses this write in 409 — its landing
   * rewrites every seat from the plan it started on: the buttons wait for it.
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly etat = signal<EtatJourJ | null>(null);
  protected readonly chargement = signal(false);
  protected readonly erreur = signal('');

  /** The name typed in the search: the roster is found, never scrolled. */
  protected readonly recherche = signal('');
  protected readonly trouves = computed(() => searchPeople(this.etat(), this.recherche()));

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
  /** The targeted publication in flight. */
  protected readonly notifyBusy = signal(false);

  protected readonly resume = computed(() => resumeDuJour(this.etat()));
  protected readonly entete = computed(() => dayHeader(this.etat()));
  protected readonly heureServeur = computed(() => heureDe(this.etat()?.maintenant));
  /** The day's consigne (issue #4), worded for the banner; empty on an ordinary day. */
  protected readonly consigne = computed(() => {
    const consigne = this.etat()?.consigne ?? null;
    return consigne
      ? { bande: bandeLabel(consigne.fermetureDebut, consigne.fermetureFin), motif: consigne.motif }
      : null;
  });

  protected readonly aPrevenir = computed(() => this.etat()?.aPrevenir ?? []);
  protected readonly prevenir = computed(() => prevenirLibelle(this.aPrevenir().length));
  protected readonly nonPublie = computed(() => nonPublieLibelle(this.aPrevenir().length));

  protected readonly alertes = computed(() =>
    (this.etat()?.alertes ?? []).map((alerte) => ({
      cle: `${alerte.type}-${alerte.standNom}-${alerte.start}-${alerte.nom ?? ''}`,
      libelle: alerteLibelle(alerte),
    })),
  );

  private readonly trous = computed<Trou[]>(() =>
    (this.etat()?.postesAPourvoir ?? []).map((poste: PosteAPourvoir) => ({
      ...poste,
      plage: plage(poste.heureDebut, poste.heureFin),
      blocage: blocageDuPoste(poste),
    })),
  );
  /** Seats the published plan had somebody on: opened this morning, by an absence. */
  protected readonly trousNouveaux = computed(() => this.trous().filter((trou) => trou.nouveau));
  /** The holes of the plan as it was published: everybody already knew them. */
  protected readonly trousConnus = computed(() => this.trous().filter((trou) => !trou.nouveau));

  /** The header's counters, worded once. */
  protected readonly compteurs = computed(() => {
    const etat = this.etat();
    return etat ? dayCounters(etat, this.trousNouveaux().length, this.trousConnus().length) : null;
  });

  /** Absences reported from the espaces and not settled yet (issue #533), worded. */
  protected readonly signalements = computed(() =>
    (this.etat()?.signalements ?? []).map((signalement: SignalementJourJ) => ({
      ...signalement,
      objet:
        signalement.portee === 'JOUR'
          ? $localize`:@@jourJ.signalement.journee:toute la journée`
          : `${signalement.standNom ?? signalement.standId ?? ''} · ${plage(signalement.heureDebut, signalement.heureFin)}`,
      motifLibelle: signalement.motif ? motifLabel(signalement.motif) : '',
    })),
  );
  /** The report being settled right now, so only its buttons wait. */
  protected readonly reportBusy = signal<number | null>(null);

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

  ngOnInit(): void {
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
  }

  /** The counters of the header lead to a section of this very page. */
  protected allerA(section: string): void {
    document.getElementById(section)?.scrollIntoView({ block: 'start' });
  }

  protected charge(postesRestants: number): string {
    return chargeRestante({ postesRestants });
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
   * seats come back in the answer — the rest of the timeslot under way
   * included, split at « now » —, so their suggestions are fetched one after
   * the other rather than all at once: each call costs the server twenty full
   * analyses of the plan.
   */
  protected async markAbsent(animateurId: string): Promise<void> {
    this.enCoursDAbsence.set(true);
    try {
      const marquee = await this.jourJ.marquerAbsent(animateurId, this.raison());
      this.fermerAbsence();
      this.recherche.set('');
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

  protected async cancelAbsence(animateurId: string, creneauId?: number): Promise<void> {
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

  /* ------------------ Reports from the espaces (issue #533) --------------- */

  /**
   * « Marquer absent et remplacer »: the absence the animateur reported is
   * observed — marked on the day or on the one timeslot — and the freed seats
   * go straight to their replacement search, like a « marquer absent ».
   */
  protected async traiterSignalement(signalementId: number): Promise<void> {
    this.reportBusy.set(signalementId);
    try {
      const marquee = await this.jourJ.traiterSignalement(signalementId);
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
      this.notifications.notify({
        title: $localize`:@@jourJ.absence.refusee:Absence refusée`,
        message: messageDe(error),
        variant: 'error',
        timeout: 0,
      });
    } finally {
      this.reportBusy.set(null);
    }
  }

  /** « Classer »: read, and nothing to change in the plan. */
  protected async classerSignalement(signalementId: number): Promise<void> {
    this.reportBusy.set(signalementId);
    try {
      await this.jourJ.classerSignalement(signalementId);
      await this.recharger();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@jourJ.signalement.classementEchec:Classement impossible`,
        message: messageDe(error),
        variant: 'error',
      });
    } finally {
      this.reportBusy.set(null);
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

  /** The candidates of one hole, already named and ranked by the server — and reachable. */
  protected candidats(posteId: string) {
    const suggestions = this.suggestionsDe(posteId);
    const etat = this.etat();
    return (suggestions?.suggestions ?? []).map((suggestion) => ({
      animateurId: suggestion.animateurId,
      nom: nomDuCandidat(etat, suggestion.animateurId),
      telephone:
        etat?.animateurs.find((animateur) => animateur.animateurId === suggestion.animateurId)
          ?.telephone ?? null,
      ameliore: compareDelta(suggestion.delta) === 'better',
      // Worth saying on the button: taking this seat adds to a day they are
      // already working, rather than filling an idle one.
      dejaDeService: dejaDeService(etat, suggestion.animateurId),
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
      await this.reparations.applyRepair(posteId, animateurId);
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
        message: $localize`:@@aujourdhui.affectation.detail:${nomDuCandidat(this.etat(), animateurId)}:nom: prend ce poste. Prévenez les personnes concernées depuis cet écran.`,
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

  /* ----------------------- Warning the people moved ---------------------- */

  /**
   * « Prévenir les N personnes »: the targeted publication, to the people whose
   * schedule differs from what they received and to nobody else. Real mail,
   * so confirmed first.
   */
  protected async notifyConcerned(): Promise<void> {
    const cibles = this.aPrevenir();
    if (cibles.length === 0) {
      return;
    }
    const ok = await this.confirm.ask({
      title: this.prevenir(),
      message: $localize`:@@aujourdhui.prevenir.confirmation:Chacune reçoit son planning à jour par e-mail. Personne d'autre n'est écrit.`,
      confirmLabel: $localize`:@@aujourdhui.prevenir.envoyer:Envoyer`,
    });
    if (!ok) {
      return;
    }
    this.notifyBusy.set(true);
    try {
      const rapport = await this.jourJ.prevenir(cibles);
      this.notifications.notify({
        title: $localize`:@@aujourdhui.prevenir.fait:${rapport.envoyes}:count: planning(s) envoyé(s)`,
        message: [...rapport.sansEmail, ...rapport.echecs].join(', '),
        variant: rapport.echecs.length > 0 || rapport.sansEmail.length > 0 ? 'warning' : 'success',
      });
      await this.recharger();
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@aujourdhui.prevenir.echec:Envoi impossible`,
        message: messageDe(error),
        variant: 'error',
        timeout: 0,
      });
    } finally {
      this.notifyBusy.set(false);
    }
  }
}

/** The readable half of whatever `ApiService` rejected with. */
function messageDe(error: unknown): string {
  return error instanceof Error && error.message
    ? error.message
    : $localize`:@@jourJ.erreur.inconnue:Erreur inattendue.`;
}
