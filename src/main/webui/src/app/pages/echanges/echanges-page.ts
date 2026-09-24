import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ActivatedRoute } from '@angular/router';
import { EchangesApi } from '../../core/api/echanges-api';
import {
  decisionNonCommuniquee,
  statutDemandeClasse,
  statutDemandeLabel,
} from '../../core/demande-echange-labels';
import {
  ConfigurationFoire,
  DemandeEchangeView,
  EchangeSimulation,
  HardMediumSoftScore,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { formatDeltaScore } from '../../core/score-format';
import { ConfirmService } from '../../shared/confirm-dialog';
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
 * Admin review of the demandes d'échange (issue #165). For each pending
 * demande: its fresh impact against the current persisted planning (score
 * delta, hard constraints newly broken, croisé or simple takeover), then two
 * explicit decisions — accept (applies the swap exactly as simulated and pins
 * both animateurs on the créneau) or refuse (with a comment sent back to the
 * animateur). Nothing is ever applied without one of these clicks; relaunching
 * the solver afterwards stays a separate, deliberate action on the solver page.
 */
@Component({
  selector: 'app-echanges-page',
  imports: [
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSlideToggleModule,
  ],
  templateUrl: './echanges-page.html',
  styleUrl: '../../../styles/demandes.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EchangesPage {
  private readonly echangesApi = inject(EchangesApi);
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
  /** `null` while the configuration has not been fetched yet. */
  protected readonly foireOpen = signal<boolean | null>(null);

  /**
   * Optional bounds of the foire, same shape as the collection window of issue
   * #291. `''` and `null` mean « pas de borne » alike — an emptied date input
   * gives the former, the server the latter.
   */
  protected readonly debut = signal<string | null>(null);
  protected readonly fin = signal<string | null>(null);

  /**
   * True when the switch is on but today is outside the bounds — the case the
   * screen must not present as « ouverte ». The server decides it: it owns the
   * bounds and the clock.
   */
  protected readonly horsFenetre = signal(false);
  protected readonly foireEnCours = signal(false);

  /**
   * « À arbitrer seulement »: the requests only the admin's word is missing
   * from — the ones « À traiter aujourd'hui » counts — the longest waiting
   * first, and nothing else on screen. `?statut=a-arbitrer`, which is what
   * the home screen links to; unticking it is the reset.
   */
  protected readonly toArbitrateOnly = signal(false);

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
    void this.reload();
  }

  protected async reload(): Promise<void> {
    this.chargement.set(true);
    try {
      const [demandes, configuration] = await Promise.all([
        this.echangesApi.list(),
        this.echangesApi.configuration(),
      ]);
      this.demandes.set(demandes);
      this.apply(configuration);
    } catch (error) {
      this.report(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /** One place to fold the server's answer back into the screen. */
  private apply(configuration: ConfigurationFoire): void {
    this.foireOpen.set(configuration.foireOuverte);
    this.debut.set(configuration.debut);
    this.fin.set(configuration.fin);
    this.horsFenetre.set(configuration.foireOuverte && !configuration.ouverteAujourdhui);
  }

  /** An emptied date input is « no bound », not an empty string to store. */
  protected majDebut(valeur: string): void {
    this.debut.set(valeur || null);
  }

  protected majFin(valeur: string): void {
    this.fin.set(valeur || null);
  }

  /** Saves the bounds without touching the switch. */
  protected async saveWindow(): Promise<void> {
    await this.basculerFoire(this.foireOpen() === true);
  }

  /**
   * Opens or closes the foire. Enforced server-side: closed, the espaces
   * animateurs turn read-only (planning still consultable and downloadable).
   */
  protected async basculerFoire(open: boolean): Promise<void> {
    this.foireEnCours.set(true);
    try {
      const configuration = await this.echangesApi.saveConfiguration({
        foireOuverte: open,
        debut: this.debut(),
        fin: this.fin(),
      });
      this.apply(configuration);
      this.notifications.notify({
        title: configuration.foireOuverte
          ? $localize`:@@echanges.foireOuverteNotif:Foire au planning ouverte : les animateurs peuvent proposer des échanges.`
          : $localize`:@@echanges.foireFermeeNotif:Foire au planning fermée : les espaces animateurs passent en consultation seule.`,
        variant: 'success',
      });
    } catch (error) {
      this.report(error);
      // Re-read the truth rather than guessing what the toggle should show.
      void this.reload();
    } finally {
      this.foireEnCours.set(false);
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
      $localize`:@@echanges.acceptee:Échange appliqué au planning et verrouillé. Régénérez le planning depuis la page Solveur pour que le reste du planning en tienne compte.`,
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
