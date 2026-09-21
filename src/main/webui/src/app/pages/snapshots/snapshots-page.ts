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
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PlanningApi } from '../../core/api/planning-api';
import { intlLocale } from '../../core/locale';
import { PlanSnapshot, RestaurationSnapshot } from '../../core/models';
import {
  InstantanePerimeError,
  PlanSnapshotStore,
  ReferencesManquantesError,
} from '../../core/plan-snapshot.store';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { SolverJobService } from '../../core/solver-job.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { confirmStaleRestore } from '../../shared/stale-snapshot-confirm';
import { StatusMessage } from '../../shared/status-message';
import { PromptDialog } from '../../shared/prompt-dialog';
import { errorPrefix } from '../../core/error-message';

/**
 * Saved plans (issue #138). Until they existed, a single plan was persisted per
 * destroyed the previous result for good.
 *
 * Restoring is disabled while a solve runs — it would be overwritten seconds
 * later — and refused by the server when the snapshot names stands, créneaux or
 * animateurs the referential no longer holds.
 */
@Component({
  selector: 'app-snapshots-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    StatusMessage,
  ],
  templateUrl: './snapshots-page.html',
  styleUrl: './snapshots-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SnapshotsPage {
  protected readonly columns = [
    'libelle',
    'score',
    'affectations',
    'creeLe',
    'fraicheur',
    'actions',
  ];
  protected readonly columnsAuto = [
    'libelleAuto',
    'scoreAuto',
    'affectationsAuto',
    'creeLeAuto',
    'fraicheurAuto',
    'actionsAuto',
  ];

  /**
   * Hand-made snapshots first, automatic ones after. One capture is taken
   * before every solve, so within a day of work the automatic ones outnumber
   * the deliberate ones and bury them.
   */
  protected readonly instantanesManuels = computed(() =>
    this.store.snapshots().filter((snapshot) => !snapshot.automatique),
  );
  protected readonly instantanesAutomatiques = computed(() =>
    this.store.snapshots().filter((snapshot) => snapshot.automatique),
  );

  /** Assignments currently persisted, to compare a snapshot against. */
  protected readonly affectationsCourantes = signal<number | null>(null);

  /**
   * The edition's **last** publication — the plan the animateurs hold and the
   * one their espace reads (issue #245). The ordering mirrors the server's
   * (`publie_le` then `id`), because it names the same row: this is the only
   * snapshot the server refuses to delete, and the screen has to say so before
   * the click rather than turn a 409 into a red banner afterwards (issue #34).
   * The publications it replaced are ordinary snapshots again.
   */
  protected readonly planPublieId = computed(() => {
    // Parsed, never compared as text: the server writes an instant, and the
    // fractional seconds it carries vary in length — « …:00Z » would then sort
    // *after* « …:00.5Z », naming the wrong row on the very screen that has to
    // name the server's. An unparseable date drops out rather than poisoning
    // the comparison with NaN.
    const publiees = this.store
      .snapshots()
      .map((snapshot) => ({ snapshot, moment: Date.parse(snapshot.publieLe ?? '') }))
      .filter((candidate) => !Number.isNaN(candidate.moment));
    if (publiees.length === 0) {
      return null;
    }
    return publiees.reduce((derniere, candidate) =>
      candidate.moment > derniere.moment ||
      (candidate.moment === derniere.moment && candidate.snapshot.id > derniere.snapshot.id)
        ? candidate
        : derniere,
    ).snapshot.id;
  });

  /** True on the plan on display, the one deletion refuses. */
  protected estPlanPublie(snapshot: PlanSnapshot): boolean {
    return snapshot.id === this.planPublieId();
  }

  /**
   * Why a snapshot carries the « publié » badge. The two sentences are the
   * whole rule: one plan is on display, the ones before it are history.
   */
  protected publicationTooltip(snapshot: PlanSnapshot): string {
    const moment = snapshot.publieLe
      ? new Date(snapshot.publieLe).toLocaleString(intlLocale())
      : '';
    return this.estPlanPublie(snapshot)
      ? $localize`:@@snapshots.published.tooltipCourant:Publié le ${moment}:moment: : c'est le plan que les animateurs ont reçu et que leur espace affiche.`
      : $localize`:@@snapshots.published.tooltipRemplace:Publié le ${moment}:moment:, puis remplacé par une publication plus récente : plus personne ne le lit.`;
  }

  /** The delete button says why it is out on the plan on display. */
  protected suppressionTooltip(snapshot: PlanSnapshot): string {
    return this.estPlanPublie(snapshot)
      ? $localize`:@@snapshots.delete.publieTooltip:Le plan publié ne peut pas être supprimé : c'est celui que les animateurs ont reçu. La prochaine publication prendra sa place.`
      : $localize`:@@common.delete:Supprimer`;
  }

  /**
   * How a snapshot differs from the plan in place — restoring blind is exactly
   * what the screen should spare the user.
   */
  protected ecart(snapshot: PlanSnapshot): string {
    const courant = this.affectationsCourantes();
    if (courant === null) {
      return '';
    }
    const delta = snapshot.nombreAffectations - courant;
    if (delta === 0) {
      return $localize`:@@snapshots.delta.same:même nombre d'affectations qu'actuellement`;
    }
    return delta > 0
      ? $localize`:@@snapshots.delta.more:${delta}:delta: affectation(s) de plus qu'actuellement`
      : $localize`:@@snapshots.delta.less:${-delta}:delta: affectation(s) de moins qu'actuellement`;
  }

  /**
   * Why a snapshot is stale, in the tooltip of its badge (issue #170). The
   * badge itself only says "périmé"; the date is what tells the user whether
   * the change was theirs a minute ago or somebody else's last week.
   */
  protected fraicheurTooltip(snapshot: PlanSnapshot): string {
    if (!snapshot.perime) {
      return $localize`:@@snapshots.fresh.tooltip:Aucune modification du référentiel depuis cette capture : le plan décrit toujours les données actuelles.`;
    }
    if (!snapshot.referenceModifieLe) {
      return $localize`:@@snapshots.stale.tooltipSansDate:Le référentiel a été modifié depuis cette capture.`;
    }
    const moment = new Date(snapshot.referenceModifieLe).toLocaleString(intlLocale());
    return $localize`:@@snapshots.stale.tooltip:Référentiel modifié le ${moment}:moment:, après cette capture : le plan ne décrit plus les données actuelles.`;
  }

  protected readonly store = inject(PlanSnapshotStore);
  protected readonly jobs = inject(SolverJobService);

  protected readonly error = signal('');
  protected readonly message = signal('');
  protected readonly enCours = signal<number | 'capture' | null>(null);
  protected readonly locked = computed(() => this.jobs.editingLocked());

  private readonly planningApi = inject(PlanningApi);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.recharger();
    void this.chargerAffectationsCourantes();
  }

  private async chargerAffectationsCourantes(): Promise<void> {
    try {
      const statut = await this.planningApi.persistedCount();
      this.affectationsCourantes.set(statut.assignments);
    } catch {
      // Without it the delta column simply stays empty.
      this.affectationsCourantes.set(null);
    }
  }

  protected async recharger(): Promise<void> {
    this.error.set('');
    try {
      await this.store.reload();
    } catch (error) {
      this.error.set(this.messageErreur(error));
    }
  }

  protected async capturer(): Promise<void> {
    const libelle = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@snapshots.capture.title:Enregistrer le plan actuel`,
      label: $localize`:@@snapshots.capture.label:Nom de l'instantané`,
      confirmLabel: $localize`:@@snapshots.capture.confirm:Enregistrer`,
    });
    if (!libelle) {
      return;
    }
    this.enCours.set('capture');
    this.error.set('');
    this.message.set('');
    try {
      await this.store.capturer(libelle);
      this.message.set(
        $localize`:@@snapshots.captured:Instantané « ${libelle}:libelle: » enregistré.`,
      );
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.enCours.set(null);
    }
  }

  protected async restaurer(snapshot: PlanSnapshot): Promise<void> {
    if (this.locked()) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@snapshots.restore.title:Restaurer cet instantané ?`,
      message: $localize`:@@snapshots.restore.message:Le plan actuellement enregistré est remplacé par « ${snapshot.libelle}:libelle: ». Enregistrez-le d'abord si vous voulez le garder.`,
      confirmLabel: $localize`:@@snapshots.restore.confirm:Restaurer`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    this.enCours.set(snapshot.id);
    this.error.set('');
    this.message.set('');
    try {
      const resultat = await this.restaurerSnapshot(snapshot);
      if (!resultat) {
        return;
      }
      await this.resolution.reload();
      this.message.set(
        $localize`:@@snapshots.restored:${resultat.affectations}:count: affectation(s) restaurée(s) depuis « ${snapshot.libelle}:libelle: ».`,
      );
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.enCours.set(null);
    }
  }

  /**
   * The server, not the row on screen, decides whether a snapshot is stale: the
   * list may have been loaded before the referential moved. So the first call
   * never forces, and the staleness refusal — which writes nothing — is turned
   * into the question the issue asks for, naming the change that caused it. The
   * badge warns before the click; this is what makes forcing a deliberate act
   * rather than a second blind « oui ».
   */
  private async restaurerSnapshot(snapshot: PlanSnapshot): Promise<RestaurationSnapshot | null> {
    try {
      return await this.store.restaurer(snapshot.id);
    } catch (error) {
      if (!(error instanceof InstantanePerimeError)) {
        throw error;
      }
      return (await confirmStaleRestore(this.confirm, error))
        ? this.store.restaurer(snapshot.id, true)
        : null;
    }
  }

  protected async remove(snapshot: PlanSnapshot): Promise<void> {
    if (this.estPlanPublie(snapshot)) {
      return;
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@snapshots.delete.title:Supprimer cet instantané ?`,
      message: $localize`:@@snapshots.delete.message:« ${snapshot.libelle}:libelle: » sera définitivement perdu.`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    this.enCours.set(snapshot.id);
    this.error.set('');
    try {
      await this.store.supprimer(snapshot.id);
    } catch (error) {
      this.error.set(this.messageErreur(error));
    } finally {
      this.enCours.set(null);
    }
  }

  protected dateLabel(snapshot: PlanSnapshot): string {
    return snapshot.creeLe ? new Date(snapshot.creeLe).toLocaleString(intlLocale()) : '';
  }

  /** A refused restore names what is missing: that list is the actionable part. */
  private messageErreur(error: unknown): string {
    if (error instanceof ReferencesManquantesError) {
      return $localize`:@@snapshots.error.references:${error.message}:message: Références manquantes : ${error.references.join(', ')}:references:`;
    }
    return errorPrefix(error);
  }
}
