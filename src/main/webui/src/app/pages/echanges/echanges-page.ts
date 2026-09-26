import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import {
  countSince,
  defaultVisitStorage,
  readLastVisit,
  writeLastVisit,
} from '../../core/derniere-visite';
import { resumePublication } from '../../core/publication';
import { EchangesApi } from '../../core/api/echanges-api';
import {
  decisionNonCommuniquee,
  statutDemandeClasse,
  statutDemandeLabel,
} from '../../core/demande-echange-labels';
import { DemandeEchangeView, EchangeSimulation, HardMediumSoftScore } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { formatDeltaScore } from '../../core/score-format';
import { ConfirmService } from '../../shared/confirm-dialog';
import { GuichetEtat } from '../../shared/guichet-etat';
import { PromptDialog } from '../../shared/prompt-dialog';
import { errorMessage } from '../../core/error-message';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { TO_ARBITRATE, oldestWaitingFirst, readToArbitrate } from './echanges-filter';

interface DemandeRow extends DemandeEchangeView {
  statutLabel: string;
  statutClasse: string;
  /**
   * Decided, and the publication that announces it has not left (issue #531):
   * what is left to publish. Said of a refusal as well as of an acceptation —
   * on this screen it is a list of pending work, not a warning about a
   * planning somebody is reading.
   */
  nonCommuniquee: boolean;
}

/**
 * Admin review of the demandes d'échange (issue #165), opening on the queue.
 * For each pending demande: its fresh impact against the current persisted
 * planning (score delta, hard constraints newly broken, croisé or simple
 * takeover), then two explicit decisions — accept (applies the swap exactly as
 * simulated and pins both animateurs on the créneau) or refuse (with a comment
 * sent back to the animateur).
 *
 * <p>An accepted swap offers its two follow-ups on the spot, on its card:
 * « Prévenir les 2 personnes » — a publication aimed at those two — and
 * « Corriger le reste », the incremental solve, with the day it touches one
 * click away. The count of requests arrived since this browser's last visit
 * sits in the header.</p>
 *
 * <p>The foire itself — its switch and its dates — is configured on
 * Paramètres › Édition; this screen keeps one line of its state (issue #720).</p>
 */
@Component({
  selector: 'app-echanges-page',
  imports: [
    DatePipe,
    GuichetEtat,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './echanges-page.html',
  styleUrl: '../../../styles/demandes.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EchangesPage implements OnInit {
  private readonly echangesApi = inject(EchangesApi);
  private readonly planningApi = inject(PlanningApi);
  private readonly jobs = inject(SolverJobService);
  private readonly visitStorage = defaultVisitStorage();
  /** This browser's previous visit, read once: the count below is against it. */
  private readonly lastVisit = readLastVisit(this.visitStorage, 'echanges');
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);
  /**
   * A solve holding the edition refuses this write in 409 — its landing
   * rewrites every seat from the plan it started on: the buttons wait for it.
   */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  protected readonly chargement = signal(false);
  protected readonly demandes = signal<DemandeEchangeView[]>([]);
  /** Fresh simulations, keyed by demande id — loaded on demand, one at a time. */
  protected readonly impacts = signal<Record<string, EchangeSimulation>>({});
  protected readonly impactEnCours = signal<string | null>(null);
  protected readonly decisionEnCours = signal<string | null>(null);
  /**
   * « À arbitrer seulement »: the requests only the admin's word is missing
   * from — the ones « À traiter aujourd'hui » counts — the longest waiting
   * first, and nothing else on screen. `?statut=a-arbitrer`, which is what
   * the home screen links to; unticking it is the reset.
   */
  protected readonly toArbitrateOnly = signal(false);

  /** Swaps just told to their two people, or whose incremental solve just left: said on their card. */
  protected readonly followUpDone = signal<Record<string, string>>({});
  protected readonly followUpBusy = signal<string | null>(null);

  /** Requests submitted since this browser's previous visit — zero on a first one. */
  protected readonly nouvelles = computed(() =>
    countSince(
      this.demandes()
        .filter((demande) => demande.statut === 'PROPOSEE' || demande.statut === 'EN_ATTENTE_CIBLE')
        .map((demande) => demande.creeLe),
      this.lastVisit,
    ),
  );

  protected readonly rows = computed<DemandeRow[]>(() =>
    this.demandes().map((demande) => ({
      ...demande,
      statutLabel: statutDemandeLabel(demande.statut),
      statutClasse: statutDemandeClasse(demande.statut),
      nonCommuniquee: decisionNonCommuniquee(demande),
    })),
  );

  /** Actionable queue: the colleague already agreed, only the admin's word is missing. */
  protected readonly awaitingDecision = computed(() => {
    const proposed = this.rows().filter((row) => row.statut === 'PROPOSEE');
    return this.toArbitrateOnly() ? oldestWaitingFirst(proposed) : proposed;
  });
  /** Still waiting for the targeted colleague: informative — refusable, but not acceptable yet. */
  protected readonly enAttenteCible = computed(() =>
    this.rows().filter((row) => row.statut === 'EN_ATTENTE_CIBLE'),
  );
  protected readonly decidees = computed(() =>
    this.rows().filter((row) => row.statut !== 'PROPOSEE' && row.statut !== 'EN_ATTENTE_CIBLE'),
  );

  constructor() {
    this.toArbitrateOnly.set(
      readToArbitrate(inject(ActivatedRoute).snapshot.queryParamMap.get('statut')),
    );
    keepViewInQueryParams(() => ({ statut: this.toArbitrateOnly() ? TO_ARBITRATE : null }));
  }

  ngOnInit(): void {
    void this.reload();
    writeLastVisit(this.visitStorage, 'echanges', new Date().toISOString());
  }

  /** An accepted swap whose announcement has not left: the two follow-ups are offered on its card. */
  protected offersFollowUps(demande: DemandeRow): boolean {
    return demande.statut === 'ACCEPTEE' && demande.nonCommuniquee;
  }

  /** « Prévenir les 2 personnes »: a publication aimed at the two people of the swap. */
  protected async prevenir(demande: DemandeRow): Promise<void> {
    const cibles = [demande.demandeurId, demande.cibleId];
    const confirmed = await this.confirm.ask({
      title: $localize`:@@echanges.prevenir.titre:Prévenir ${demande.demandeurNom}:demandeur: et ${demande.cibleNom}:cible: ?`,
      message: $localize`:@@echanges.prevenir.message:Ces deux personnes reçoivent leur planning à jour et leur espace l'affiche. Les autres personnes à prévenir le seront à la prochaine publication.`,
      confirmLabel: $localize`:@@echanges.prevenir.confirmer:Prévenir`,
    });
    if (!confirmed) {
      return;
    }
    this.followUpBusy.set(demande.id);
    try {
      const rapport = await this.planningApi.publishTo(cibles);
      const resume = resumePublication(rapport);
      this.followUpDone.set({ ...this.followUpDone(), [demande.id]: resume.titre });
      this.notifications.notify({
        title: resume.titre,
        message: resume.details,
        variant: rapport.echecs.length > 0 ? 'error' : 'success',
      });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.followUpBusy.set(null);
    }
  }

  /** « Corriger le reste »: the incremental solve, which re-fills what the swap left and keeps the rest. */
  protected async corriger(demande: DemandeRow): Promise<void> {
    this.followUpBusy.set(demande.id);
    try {
      await this.jobs.submitSolveIncremental(
        { animateurIds: [], jours: [], standIds: [] },
        undefined,
        this.jobs.solverBusy(),
      );
      this.followUpDone.set({
        ...this.followUpDone(),
        [demande.id]: $localize`:@@echanges.corriger.lance:Replanification incrémentale lancée.`,
      });
    } catch (error) {
      this.report(error);
    } finally {
      this.followUpBusy.set(null);
    }
  }

  protected async reload(): Promise<void> {
    this.chargement.set(true);
    try {
      this.demandes.set(await this.echangesApi.list());
    } catch (error) {
      this.report(error);
    } finally {
      this.chargement.set(false);
    }
  }

  protected impactDe(demande: DemandeRow): EchangeSimulation | null {
    return this.impacts()[demande.id] ?? null;
  }

  /** The delta is a score object — rendered through the shared formatter, never interpolated raw. */
  protected formatDelta(delta: HardMediumSoftScore): string {
    return formatDeltaScore(delta);
  }

  protected async chargerImpact(demande: DemandeRow): Promise<void> {
    this.impactEnCours.set(demande.id);
    try {
      const impact = await this.echangesApi.impact(demande.id);
      this.impacts.set({ ...this.impacts(), [demande.id]: impact });
    } catch (error) {
      this.report(error);
    } finally {
      this.impactEnCours.set(null);
    }
  }

  protected async accepter(demande: DemandeRow): Promise<void> {
    const confirmed = await this.confirm.ask({
      title: $localize`:@@echanges.accepterTitre:Accepter l'échange de ${demande.demandeurNom}:demandeur: ?`,
      message: $localize`:@@echanges.accepterMessage:L'échange avec ${demande.cibleNom}:cible: sera appliqué immédiatement au planning et verrouillé sur ce créneau. La demande ne pourra plus être refusée ensuite.`,
      confirmLabel: $localize`:@@echanges.accepterConfirm:Accepter`,
    });
    if (!confirmed) {
      return;
    }
    await this.decider(
      demande,
      'acceptation',
      null,
      $localize`:@@echanges.acceptee2:Échange appliqué et verrouillé. Prévenez les deux personnes ou corrigez le reste depuis sa carte.`,
    );
  }

  protected async refuser(demande: DemandeRow): Promise<void> {
    const commentaire = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@echanges.refuserTitre:Refuser la demande de ${demande.demandeurNom}:demandeur:`,
      label: $localize`:@@echanges.refuserLabel:Motif du refus (transmis à l'animateur)`,
      confirmLabel: $localize`:@@echanges.refuserConfirm:Refuser`,
    });
    if (commentaire === null) {
      return;
    }
    await this.decider(
      demande,
      'refus',
      commentaire,
      $localize`:@@echanges.refusee:Demande refusée, le planning reste inchangé.`,
    );
  }

  private async decider(
    demande: DemandeRow,
    action: 'acceptation' | 'refus',
    commentaire: string | null,
    confirmation: string,
  ): Promise<void> {
    this.decisionEnCours.set(demande.id);
    try {
      await this.echangesApi.decide(demande.id, action, commentaire);
      this.notifications.notify({ title: confirmation, variant: 'success', timeout: 5000 });
      await this.reload();
    } catch (error) {
      this.report(error);
    } finally {
      this.decisionEnCours.set(null);
    }
  }

  private report(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@crud.error:Erreur`,
      message: errorMessage(error),
      variant: 'error',
    });
  }
}
