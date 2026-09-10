import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  signal,
  viewChild
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { PlanningApi } from '../../core/api/planning-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { TODAY_ANCHOR, DateMockService } from '../../core/date-mock.service';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { OutputPanel } from '../../shared/output-panel';
import { APP_VERSION, REPO_URL } from '../../version';
import { StatusMessage } from '../../shared/status-message';
import { YamlValidator } from './yaml-validator';
import { errorMessage, errorPrefix } from '../../core/error-message';

/**
 * Typed back instead of the edition name when no edition is loaded. Left
 * untranslated on purpose: a keyword whose spelling follows the interface
 * language is a keyword an administrator gets wrong after a language switch.
 */
export const CLEAR_KEYWORD = 'VIDER';

/**
 * Raw dump of the last constraint analysis (`GET /api/constraints`):
 * global score, unfilled seats, feasibility and per-constraint score/match
 * count. Deliberately excludes animateurs/creneaux/postes — this is a debug
 * aid, kept separate from the "Constraints" page's business-friendly card
 * view. The backend keeps the last diagnostic in memory for the life of the
 * server process, so this survives a browser refresh (it is only lost if the
 * server itself restarts).
 *
 * Also hosts the database maintenance card (emptying the database, the
 * pgAdmin and Mailpit links): low-level tooling that belongs with the other
 * diagnostics rather than on the day-to-day Data page.
 */
@Component({
  selector: 'app-debug-page',
  imports: [FormsModule, MatCardModule, MatButtonModule, MatFormFieldModule, MatIconModule,
    MatInputModule, MatProgressBarModule, OutputPanel, StatusMessage, YamlValidator],
  templateUrl: './debug-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class DebugPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly output = signal('');
  protected readonly resetting = signal(false);
  protected readonly appVersion = APP_VERSION;
  protected readonly repoUrl = REPO_URL;

  /** The server-side solver lock: emptying the database under a solve would corrupt it. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  private readonly api = inject(ApiService);
  private readonly planningApi = inject(PlanningApi);
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly notifications = inject(NotificationService);
  private readonly jobs = inject(SolverJobService);
  private readonly recopie = inject(ConfirmationRecopie);
  private readonly editions = inject(EditionStore);
  private readonly instantane = inject(InstantaneAvantAction);
  // Emptying the database moves the resolution stamp, the "data
  // edited since the last solve" stamp and the feasibility diagnostic: the
  // stores the toolbar warnings read are refreshed here, exactly as the Data
  // page does after its own imports.
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly problemes = inject(ProblemesStore);
  protected readonly dates = inject(DateMockService);

  protected readonly todayAnchor = TODAY_ANCHOR;
  protected readonly dateDuJourErreur = signal('');

  /**
   * Resolves only once the field is rendered, which is itself conditional on
   * the server saying the setting may be used — so this is what the deep link
   * of the toolbar warning has to wait for.
   */
  private readonly champDateDuJour = viewChild<ElementRef<HTMLInputElement>>('champDateDuJour');

  /**
   * `?focus=date-du-jour`, set by the toolbar warning. Read once from the
   * snapshot and honoured once: the point is to land on the control, not to
   * steal the focus back every time the page re-renders. An unknown value
   * simply does nothing (decision 0012: reading view state is tolerant).
   */
  private focusEnAttente =
    inject(ActivatedRoute, { optional: true })?.snapshot.queryParamMap.get('focus') ===
    TODAY_ANCHOR;

  constructor() {
    void this.chargerMailConfig();
    void this.refresh();
    effect(() => {
      const champ = this.champDateDuJour();
      if (!champ || !this.focusEnAttente) {
        return;
      }
      this.focusEnAttente = false;
      // Scrolling is the nicety, the focus is the point: jsdom has no
      // scrollIntoView, and neither does an old browser.
      champ.nativeElement.scrollIntoView?.({ block: 'center' });
      champ.nativeElement.focus();
    });
  }

  /**
   * Saved on change, with no Validate button: the field holds one value, and a
   * second click to confirm a date somebody just picked buys nothing.
   *
   * <p>A refusal is shown next to the field rather than as a toast — the most
   * likely one is "this server is not in development mode", which is an answer
   * about that control and should stay under it.
   */
  protected async onDateDuJour(valeur: string): Promise<void> {
    this.dateDuJourErreur.set('');
    try {
      await this.dates.set(valeur);
    } catch (error) {
      this.dateDuJourErreur.set(errorMessage(error));
    }
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const view = await this.constraintsApi.catalogue();
      this.output.set(JSON.stringify(view, null, 2));
    } catch (error) {
      this.output.set('');
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  // Empties the database entirely: no scenario is reloaded, so the app is left
  // with a blank dataset until a sample is loaded again from the Data page.
  protected async onResetDatabase(): Promise<void> {
    // Guards against a race: the button is disabled while a solver job runs,
    // but a job could have started between the last render and the click.
    if (this.solverBusy()) {
      const description = this.jobs.activeJobDescription();
      // A notification, not the output panel: the panel holds the constraints
      // dump and must not be clobbered by a lock warning.
      this.notifications.notify({
        title: $localize`:@@dataSetup.lockedByJob:${description}:description: La configuration des données est verrouillée jusqu'à la fin.`,
        variant: 'warning'
      });
      return;
    }
    // The reset is scoped to the current edition (`clearDatabase` deletes
    // where `edition_id` matches), so what has to be typed back is that
    // edition's name — the very thing that is about to be emptied. Without a
    // loaded edition the name is unknown, and a keyword takes its place rather
    // than a confirmation naming an edition we cannot vouch for.
    // `admin-shell` recharge le store sans attendre, donc un lien direct vers
    // cette page peut arriver ici avant la réponse : on attend plutôt que de
    // retomber sur un mot-clé de cinq lettres pour une action qui supprime une
    // vraie édition. `trim() || null` et non `?? null` : un nom vide ferait
    // choisir le message générique tout en exigeant une valeur vide, que
    // `PromptDialog` refuse — le reset deviendrait inatteignable.
    if (this.editions.courant() === null) {
      await this.editions.reload();
    }
    const nomEdition = this.editions.courant()?.nom?.trim() || null;
    const confirmed = await this.recopie.demander({
      title: $localize`:@@dataSetup.resetConfirmTitle:Vider la base de données ?`,
      message: nomEdition
        ? $localize`:@@dataSetup.resetPromptMessage:Tous les stands, créneaux, animateurs, affectations et ajustements manuels de l'édition « ${nomEdition}:edition: » sont supprimés, et rien n'est rechargé. Les autres éditions ne sont pas touchées.`
        : $localize`:@@dataSetup.resetPromptMessageSansEdition:Tous les stands, créneaux, animateurs, affectations et ajustements manuels de l'édition courante sont supprimés, et rien n'est rechargé. Les autres éditions ne sont pas touchées.`,
      valeurAttendue: nomEdition ?? CLEAR_KEYWORD,
      confirmLabel: $localize`:@@dataSetup.resetConfirmLabel:Vider`
    });
    if (!confirmed) {
      return;
    }
    await this.instantane.proposer($localize`:@@dataSetup.action.reset:vider la base`);
    this.resetting.set(true);
    try {
      await this.planningApi.reset();
      this.planningState.set(null);
      await Promise.all([
        this.referenceData.reload(),
        this.resolution.reload(),
        this.solverSettings.refresh(),
        this.problemes.reloadFeasibility()
      ]);
      this.notifications.notify({
        title: $localize`:@@dataSetup.resetDone:Base de données vidée. Chargez un planning d'exemple pour la repeupler.`,
        variant: 'info'
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@debug.resetFailed:Base de données non vidée`,
        message: errorMessage(error),
        variant: 'error'
      });
    } finally {
      this.resetting.set(false);
    }
  }

  /** Exercises the info/warning/alert path end-to-end: snack bar and the persisted Notifications log. */
  protected sendTestNotification(severity: 'info' | 'warning' | 'alert'): void {
    const variant = severity === 'alert' ? 'error' : severity;
    this.notifications.notify({
      title: $localize`:@@debug.testNotification.title:Notification de test (${severity}:severity:)`,
      message: $localize`:@@debug.testNotification.message:Générée depuis la page Débogage.`,
      variant
    });
  }

  /**
   * Thrown straight from a template event handler, so it reaches Angular's
   * `ErrorHandler` the same way a real unhandled bug would — that's the
   * handler Sentry's `createErrorHandler()` replaces (see observability.ts),
   * so this exercises the exact same path a genuine frontend crash takes.
   */
  protected triggerFrontException(): void {
    throw new Error('Test exception (bouton Débogage / Exception front)');
  }

  /** Admin address the mail notifications go to — null once loaded when MAIL_ADMIN is not set. */
  protected readonly mailAdmin = signal<string | null | undefined>(undefined);
  protected readonly mailTestBusy = signal(false);

  private async chargerMailConfig(): Promise<void> {
    try {
      const config = await this.api.get<{ adminEmail: string | null }>('/api/debug/mail-config');
      this.mailAdmin.set(config.adminEmail);
    } catch {
      // Endpoint unreachable: leave the state unknown, no warning either way.
    }
  }

  /**
   * Really sends a mail to the admin address — and surfaces the failure,
   * unlike the business sends which are best-effort: verifying the SMTP
   * plumbing is the whole point of this button.
   */
  protected async envoyerMailTest(): Promise<void> {
    this.mailTestBusy.set(true);
    try {
      const result = await this.api.post<{ adminEmail: string }>('/api/debug/test-mail', {});
      this.notifications.notify({
        title: $localize`:@@debug.mailTest.envoye:Mail de test envoyé à ${result.adminEmail}:adresse:.`,
        variant: 'success',
        timeout: 6000
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@debug.mailTest.echec:Échec de l'envoi du mail de test`,
        message: errorMessage(error),
        variant: 'error'
      });
    } finally {
      this.mailTestBusy.set(false);
    }
  }

  /**
   * The endpoint always throws: `GlobalExceptionMapper` reports it to
   * Sentry/Bugsink server-side before answering 500, which is the point of
   * this button. The resulting rejection is expected and not worth surfacing.
   */
  protected async triggerBackException(): Promise<void> {
    try {
      await this.api.post('/api/debug/test-exception', {});
    } catch {
      // Expected: see the docstring above.
    }
  }
}

