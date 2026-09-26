import {
  ChangeDetectionStrategy,
  Component,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { NotificationService } from '../../core/notification.service';
import { OutputPanel } from '../../shared/output-panel';
import { versionUrl } from '../../core/version-link';
import { APP_VERSION, REPO_URL } from '../../version';
import { NewWindowLink } from '../../shared/new-window-link';
import { StatusMessage } from '../../shared/status-message';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { OngletDebug, readOngletDebug } from './debug';
import { errorMessage, errorPrefix } from '../../core/error-message';

/**
 * Raw dump of the last constraint analysis (`GET /api/constraints`):
 * global score, unfilled seats, feasibility and per-constraint score/match
 * count. Deliberately excludes animateurs/creneaux/postes — this is a debug
 * aid, kept separate from the "Constraints" page's business-friendly card
 * view. The backend keeps the last diagnostic in memory for the life of the
 * server process, so this survives a browser refresh (it is only lost if the
 * server itself restarts).
 *
 * <p>Only what is raw or technical stays here, served on every instance and
 * reached by its address or the Ctrl+K palette: « Résolution » (default — the
 * raw analysis, the version, the API docs) and « Vérifications » (a test
 * notification, a test exception, a test mail, Mailpit and pgAdmin). The
 * gestures of an organiser or an operator moved to the screen of their
 * question: the bundled scenarios and the YAML validator to Fichiers,
 * emptying an edition to Éditions, the simulated clock to Paramètres ›
 * Instance. The output panel stays under the tabs: what an action answered is
 * read after it, and changing tab is not a reason to lose it.</p>
 */
@Component({
  selector: 'app-debug-page',
  imports: [
    NewWindowLink,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatIconModule,
    MatProgressBarModule,
    OutputPanel,
    StatusMessage,
  ],
  templateUrl: './debug-page.html',
  styleUrls: ['./debug-page.css', './debug-diagnostic.css'],
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
  protected readonly appVersion = APP_VERSION;
  protected readonly appVersionUrl = versionUrl(APP_VERSION);
  protected readonly repoUrl = REPO_URL;

  private readonly adminApi = inject(AdminApi);
  private readonly constraintsApi = inject(ConstraintsApi);
  private readonly notifications = inject(NotificationService);
  protected readonly onglet = signal<OngletDebug>('resolution');

  private readonly route = inject(ActivatedRoute, { optional: true });

  constructor() {
    // Followed rather than read once, like Diagnostic: the router reuses this
    // component when one navigates to `/debug` again with another `onglet` —
    // from the palette, typically. `replaceState` (ADR 0018) emits nothing, so
    // the writer below cannot feed this subscription.
    this.route?.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.onglet.set(readOngletDebug(params.get('onglet')));
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'resolution' ? null : this.onglet(),
    }));
  }

  ngOnInit(): void {
    void this.chargerMailConfig();
    void this.refresh();
  }

  protected changerOnglet(onglet: OngletDebug): void {
    this.onglet.set(onglet);
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
