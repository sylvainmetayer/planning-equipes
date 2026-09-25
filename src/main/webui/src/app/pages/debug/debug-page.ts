import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  OnInit,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { PlanningApi } from '../../core/api/planning-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { TODAY_ANCHOR, DateMockService } from '../../core/date-mock.service';
import { EditionStore } from '../../core/edition.store';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { GelEditionNotice } from '../../shared/gel-edition-notice';
import { OutputPanel } from '../../shared/output-panel';
import { versionUrl } from '../../core/version-link';
import { APP_VERSION, REPO_URL } from '../../version';
import { StatusMessage } from '../../shared/status-message';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { OngletDebug, readOngletDebug } from './debug';
import { ScenarioPreenregistre } from './scenario-preenregistre';
import { YamlValidator } from './yaml-validator';
import { errorMessage, errorPrefix } from '../../core/error-message';
import { NewWindowLink } from '../../shared/new-window-link';

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
 * pgAdmin and Mailpit links) and the pre-recorded scenario picker: low-level
 * tooling that belongs with the other diagnostics rather than on the
 * day-to-day Paramètres page. What an organiser does with a scenario — upload
 * one, write one out — stays on the Imports and Exports screens.
 *
 * <p>Four tabs since issue #606, the same mechanics as Diagnostic and Imports:
 * « Résolution » (default — the raw analysis, the version, the API docs),
 * « Vérifications » (a test notification, a test exception, a test mail,
 * Mailpit, and the frozen date where it is allowed), « Données » (the database
 * and the bundled scenarios) and « Validateur YAML ». The output panel stays
 * under the tabs: what an action answered is read after it, and changing tab
 * is not a reason to lose it.</p>
 */
@Component({
  selector: 'app-debug-page',
  imports: [
    GelEditionNotice,
    NewWindowLink,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    OutputPanel,
    ScenarioPreenregistre,
    StatusMessage,
    YamlValidator,
  ],
  templateUrl: './debug-page.html',
  styleUrls: [
    './debug-page.css',
    './debug-date-du-jour.css',
    './debug-diagnostic.css',
    './scenario-preenregistre.css',
    './yaml-validator.css',
  ],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DebugPage implements OnInit {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly output = signal('');

  /**
   * The serialised constraint diagnostic, loaded on demand (issue #589). The
   * Solveur page used to dump it into its own output panel, where it chased
   * away the state and error messages that share that panel and told an
   * organiser nothing the cards above it did not already say. It is a technical
   * artefact, so it lives here, behind a button.
   */
  protected readonly diagnosticJson = signal<string | null>(null);
  protected readonly diagnosticBusy = signal(false);
  protected readonly resetting = signal(false);
  protected readonly appVersion = APP_VERSION;
  protected readonly appVersionUrl = versionUrl(APP_VERSION);
  protected readonly repoUrl = REPO_URL;

  /** The server-side solver lock: emptying the database under a solve would corrupt it. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());

  /**
   * A frozen family of the referential (ADR 0052): the server refuses to empty
   * an edition while any family is frozen, so the button says it before the click.
   */
  protected readonly gel = injectGelReferentiel();

  private readonly adminApi = inject(AdminApi);
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

  protected readonly onglet = signal<OngletDebug>('resolution');

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
  private readonly route = inject(ActivatedRoute, { optional: true });

  private focusPending = this.route?.snapshot.queryParamMap.get('focus') === TODAY_ANCHOR;

  constructor() {
    // Followed rather than read once, like Diagnostic: the router reuses this
    // component when one navigates to `/debug` again with another `onglet` —
    // from the menu, from the date-frozen indicator, from the « page Débogage »
    // pointers of Paramètres and Imports. `replaceState` (ADR 0018) emits
    // nothing, so the writer below cannot feed this subscription.
    this.route?.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      // `?focus=date-du-jour` names a control that lives on the Vérifications
      // tab: an old link that predates the tabs still lands on its field.
      const demande = params.get('onglet');
      this.onglet.set(
        demande === null && params.get('focus') === TODAY_ANCHOR
          ? 'verifications'
          : readOngletDebug(demande),
      );
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'resolution' ? null : this.onglet(),
    }));
    effect(() => {
      const champ = this.champDateDuJour();
      if (!champ || !this.focusPending) {
        return;
      }
      this.focusPending = false;
      // Scrolling is the nicety, the focus is the point: jsdom has no
      // scrollIntoView, and neither does an old browser.
      champ.nativeElement.scrollIntoView?.({ block: 'center' });
      champ.nativeElement.focus();
    });
  }

  ngOnInit(): void {
    void this.chargerMailConfig();
    void this.refresh();
  }

  protected changerOnglet(onglet: OngletDebug): void {
    this.onglet.set(onglet);
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
    // Clearing the date clears the time: a time alone is refused server-side.
    await this.saveHorloge(valeur, valeur ? this.dates.heureMock() : '');
  }

  /** Same save on change; an empty time gives the wall clock back its hours. */
  protected async onHeureMock(valeur: string): Promise<void> {
    await this.saveHorloge(this.dates.dateDuJour(), valeur ?? '');
  }

  private async saveHorloge(date: string, heure: string): Promise<void> {
    this.dateDuJourErreur.set('');
    try {
      await this.dates.set(date, heure);
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
        variant: 'warning',
      });
      return;
    }
    // Same race for the freeze: another session may have frozen a family since
    // this page read it — the server would refuse anyway, the notice says why.
    if (this.gel.anyFrozen()) {
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
      confirmLabel: $localize`:@@dataSetup.resetConfirmLabel:Vider`,
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
        this.problemes.reloadFeasibility(),
      ]);
      this.notifications.notify({
        title: $localize`:@@dataSetup.resetDone:Base de données vidée. Chargez un planning d'exemple pour la repeupler.`,
        variant: 'info',
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@debug.resetFailed:Base de données non vidée`,
        message: errorMessage(error),
        variant: 'error',
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
      variant,
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
      const config = await this.adminApi.mailConfig();
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
      const result = await this.adminApi.sendTestMail();
      this.notifications.notify({
        title: $localize`:@@debug.mailTest.envoye:Mail de test envoyé à ${result.adminEmail}:adresse:.`,
        variant: 'success',
        timeout: 6000,
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@debug.mailTest.echec:Échec de l'envoi du mail de test`,
        message: errorMessage(error),
        variant: 'error',
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
      await this.adminApi.triggerTestException();
    } catch {
      // Expected: see the docstring above.
    }
  }

  /**
   * Re-derives the diagnostic from the plan currently persisted — one score
   * calculation, no solve — and shows it whole. An edition that has never been
   * analysed answers an empty view, which is said in words rather than left as
   * a lone pair of braces.
   */
  protected async onLoadDiagnostic(): Promise<void> {
    this.diagnosticBusy.set(true);
    try {
      const diagnostic = await this.constraintsApi.diagnose();
      this.diagnosticJson.set(
        diagnostic.analysedAt === null
          ? $localize`:@@debug.diagnostic.empty:Aucun planning n'a encore été analysé sur cette édition.`
          : JSON.stringify(diagnostic, null, 2),
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.diagnosticBusy.set(false);
    }
  }
}
