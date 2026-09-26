import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { PlanningApi } from '../../core/api/planning-api';
import { EditionStore } from '../../core/edition.store';
import { errorMessage } from '../../core/error-message';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { GelEditionNotice } from '../../shared/gel-edition-notice';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';

/**
 * Typed back instead of the edition name when no edition is loaded. Left
 * untranslated on purpose: a keyword whose spelling follows the interface
 * language is a keyword an administrator gets wrong after a language switch.
 */
export const CLEAR_KEYWORD = 'VIDER';

/**
 * « Vider cette édition »: what `PlanningPersistenceService.TABLES_A_VIDER`
 * deletes where `edition_id` matches — stands, timeslots, animateurs, seats,
 * manual adjustments, locks, day validations, swap requests, consignes and
 * their presets — and nothing reloaded. It used to be « Vider la base de
 * données » on the Débogage page, which said more than it did: the reset is
 * scoped to one edition, so it lives next to the editions, and its
 * confirmation names what goes and what is left — its game categories,
 * locations, day templates, snapshots and settings, the other editions, the
 * instance. A table added to that list is a sentence to change here.
 *
 * <p>The same guards as before: refused while a solve runs (another browser
 * may have started one), and while a family of the referential is frozen
 * (ADR 0052) — the server refuses both anyway, the card says it first.</p>
 */
@Component({
  selector: 'app-empty-edition-card',
  imports: [GelEditionNotice, MatButtonModule, MatCardModule, MatIconModule],
  template: `
    <mat-card appearance="outlined" class="page-card">
      <mat-card-header>
        <h2 mat-card-title i18n="@@editions.vider.title">Vider cette édition</h2>
        <mat-card-subtitle i18n="@@editions.vider.subtitle">
          Supprime les stands, créneaux, animateurs et le planning de l'édition consultée ; les autres éditions ne bougent pas.
        </mat-card-subtitle>
      </mat-card-header>
      <mat-card-content>
        <app-gel-edition-notice operation="reset" />
      </mat-card-content>
      <mat-card-actions class="card-actions">
        <button
          matButton="outlined"
          class="danger-action"
          [disabled]="solverBusy() || resetting() || gel.anyFrozen()"
          (click)="empty()"
        >
          <mat-icon>delete_sweep</mat-icon>
          <ng-container i18n="@@editions.vider.action">Vider cette édition</ng-container>
        </button>
      </mat-card-actions>
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EmptyEditionCard {
  protected readonly resetting = signal(false);

  /** The server-side solver lock: emptying an edition under a solve would corrupt it. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  /**
   * A frozen family of the referential (ADR 0052): the server refuses to empty
   * an edition while any family is frozen, so the button says it before the click.
   */
  protected readonly gel = injectGelReferentiel();

  private readonly planningApi = inject(PlanningApi);
  private readonly notifications = inject(NotificationService);
  private readonly jobs = inject(SolverJobService);
  private readonly recopie = inject(ConfirmationRecopie);
  private readonly editions = inject(EditionStore);
  private readonly instantane = inject(InstantaneAvantAction);
  // Emptying an edition moves the resolution stamp, the "data edited since
  // the last solve" stamp and the feasibility diagnostic: the stores the
  // toolbar warnings read are refreshed here, as after an import.
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly problemes = inject(ProblemesStore);

  protected async empty(): Promise<void> {
    // Guards against a race: the button is disabled while a solver job runs,
    // but a job could have started between the last render and the click.
    if (this.solverBusy()) {
      const description = this.jobs.activeJobDescription();
      this.notifications.notify({
        title: $localize`:@@dataSetup.lockedByJob:${description}:description: La configuration des données est verrouillée jusqu'à la fin.`,
        variant: 'warning',
      });
      return;
    }
    // Same race for the freeze: another session may have frozen a family since
    // this page read it — the server would refuse anyway, the notice says why.
    if (this.gel.anyFrozen()) {
      return;
    }
    // What has to be typed back is the name of the edition about to be
    // emptied. The shell reloads the editions without waiting, so a direct
    // link may land here before the answer: wait for it rather than fall back
    // on a five-letter keyword for an action that empties a real edition.
    // `trim() || null` and not `?? null`: a blank name would pick the generic
    // message while demanding an empty value, which `PromptDialog` refuses —
    // the reset would become unreachable.
    if (this.editions.courant() === null) {
      await this.editions.reload();
    }
    const nomEdition = this.editions.courant()?.nom?.trim() || null;
    // What goes, then what stays: two sentences, each short enough to be read.
    const deleted = nomEdition
      ? $localize`:@@editions.vider.message:Sont supprimés de « ${nomEdition}:edition: » : stands, créneaux, animateurs, affectations, ajustements manuels, verrouillages, validations de journées, demandes d'échange, consignes et leurs préréglages.`
      : $localize`:@@editions.vider.messageSansNom:Sont supprimés de l'édition courante : stands, créneaux, animateurs, affectations, ajustements manuels, verrouillages, validations de journées, demandes d'échange, consignes et leurs préréglages.`;
    const kept = $localize`:@@editions.vider.restent:Restent ses typologies, emplacements, journées types, instantanés et paramètres, les autres éditions et l'instance.`;
    const confirmed = await this.recopie.demander({
      title: nomEdition
        ? $localize`:@@editions.vider.confirmTitle:Vider l'édition « ${nomEdition}:edition: » ?`
        : $localize`:@@editions.vider.confirmTitleSansNom:Vider l'édition courante ?`,
      message: `${deleted} ${kept}`,
      valeurAttendue: nomEdition ?? CLEAR_KEYWORD,
      confirmLabel: $localize`:@@dataSetup.resetConfirmLabel:Vider`,
    });
    if (!confirmed) {
      return;
    }
    await this.instantane.proposer($localize`:@@editions.vider.instantane:vider l'édition`);
    this.resetting.set(true);
    try {
      await this.planningApi.reset();
      this.planningState.set(null);
      await Promise.all([
        this.referenceData.reload(),
        this.resolution.reload(),
        this.solverSettings.refresh(),
        this.problemes.reloadFeasibility(),
      ]);
      this.notifications.notify({
        title: $localize`:@@editions.vider.done:Édition vidée. Importez un fichier ou chargez un exemple pour la remplir.`,
        variant: 'info',
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@editions.vider.failed:Édition non vidée`,
        message: errorMessage(error),
        variant: 'error',
      });
    } finally {
      this.resetting.set(false);
    }
  }
}
