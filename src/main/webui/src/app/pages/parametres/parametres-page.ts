import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  OnInit,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { BRANDING, slugMarque } from '../../core/branding';
import { EtatSauvegarde } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { OutputPanel } from '../../shared/output-panel';
import { StatusMessage } from '../../shared/status-message';
import { ParametresLegauxCard } from './parametres-legaux';
import { ParametresQualiteCard } from './parametres-qualite';
import { ParametresNotificationsPanel } from './parametres-notifications';
import { errorPrefix } from '../../core/error-message';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { OngletParametres, readOngletParametres } from './parametres';
import { SingleKeyShortcutsToggle } from '../../shared/single-key-shortcuts-toggle';

/**
 * Typed back before a SQL dump is replayed. Left untranslated on purpose: a
 * keyword whose spelling follows the interface language is a keyword an
 * administrator gets wrong after a language switch.
 */
export const REPLACE_KEYWORD = 'REMPLACER';

/**
 * The settings page, four tabs instead of one long scroll (issue #606), the
 * same mechanics as Diagnostic and Imports — a toggle group, the tab carried
 * by `?onglet=`, the default tab writing nothing:
 *
 * - "Légaux" (default) — the legal parameters and the meal break: the floor an
 *   edition is checked against, which is what this page is opened for.
 * - "Édition" — the rest of what the `X-Edition-Id` partition scopes: the
 *   ninja typologie, the organisational-quality thresholds, plus pointers to
 *   what stays on its own screen (solver duration, foire aux échanges,
 *   constraint toggles, découpage settings next to the generation that reads
 *   them, and the three scenario operations — load a bundled one on Débogage,
 *   import a file under Imports, write one out under Exports).
 * - "E-mails automatiques" — what the edition sends of its own accord: to the
 *   administrator at the end of a solve, to the animateurs as reminders and
 *   relances. Not "Notifications", which is the name of another page.
 * - "Globaux" — the SQL dump import/export and the automatic backup: both take
 *   the WHOLE database, every edition included, so neither belongs to any
 *   edition.
 *
 * The feasibility banner stays above the tabs: it speaks of the edition, not
 * of a tab. So does the output panel — an error answered on one tab survives
 * a move to another.
 *
 * Also runs the solver-free feasibility check on entry, as the former Données
 * page did: a structurally impossible planning is called out here rather than
 * after a fruitless solve.
 */
@Component({
  selector: 'app-parametres-page',
  imports: [
    SingleKeyShortcutsToggle,
    DatePipe,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatTooltipModule,
    MatSlideToggleModule,
    RouterLink,
    FeasibilityBanner,
    OutputPanel,
    ParametresLegauxCard,
    ParametresQualiteCard,
    ParametresNotificationsPanel,
    StatusMessage,
  ],
  templateUrl: './parametres-page.html',
  styleUrl: './parametres.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresPage implements OnInit {
  /** Dump named after the deployment, so two instances' exports never collide in a downloads folder. */
  private readonly nomFichierDump = `${slugMarque(inject(BRANDING).productName)}.sql`;

  protected readonly output = signal('');
  protected readonly transferBusy = signal(false);

  /** Admin address configured server-side, `null` when mail is disabled entirely. */
  protected readonly adminEmail = signal<string | null>(null);
  protected readonly mailFinResolutionBusy = signal(false);
  protected readonly mailFinResolution = computed(() => this.solverSettings.mailFinResolution());

  /** The server-side solver lock: also covers a solve/analysis from another browser. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());
  /**
   * Edition-scoped writes (legal parameters, ninja typologie) follow the
   * per-edition lock: a solve running on ANOTHER edition leaves them
   * available.
   */
  protected readonly editionLocked = computed(() => this.jobs.editingLocked());
  /** SQL dump replay rewrites the WHOLE database, every edition included: locked by any running job. */
  protected readonly transferLocked = computed(() => this.transferBusy() || this.solverBusy());

  private readonly sqlInput = viewChild.required<ElementRef<HTMLInputElement>>('sqlInput');
  /** Pre-solve diagnostic shown by the banner at the top of the page. */
  protected readonly problemes = inject(ProblemesStore);
  protected readonly store = inject(ReferenceDataStore);

  private readonly adminApi = inject(AdminApi);
  // Replaying a dump replaces the same data a scenario import does: the stores
  // to reload afterwards are the import's own set, not a second list.
  private readonly scenarioImport = inject(ScenarioImportService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly recopie = inject(ConfirmationRecopie);
  private readonly instantane = inject(InstantaneAvantAction);

  private readonly jobs = inject(SolverJobService);
  protected readonly solverSettings = inject(SolverSettingsService);
  private readonly notifications = inject(NotificationService);

  protected readonly onglet = signal<OngletParametres>('legaux');

  private readonly route = inject(ActivatedRoute);

  constructor() {
    // Followed rather than read once, like Diagnostic: clicking « Paramètres »
    // in the menu from `/parametres?onglet=globaux`, or the « paramètres
    // légaux » link of Pauses while another tab is open, navigates to this very
    // route with another `onglet`, and the router reuses the component instead
    // of building it again. `replaceState` (ADR 0018) emits nothing here, so
    // the effect below cannot feed this subscription.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.onglet.set(readOngletParametres(params.get('onglet')));
    });
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'legaux' ? null : this.onglet(),
    }));
    void this.problemes.reloadFeasibility();
    void this.crud.reload();
  }

  ngOnInit(): void {
    void this.chargerReglagesNotification();
    void this.chargerSauvegarde();
  }

  protected changerOnglet(onglet: OngletParametres): void {
    this.onglet.set(onglet);
  }

  /* --------------------- Notification de fin de résolution -------------------- */

  /**
   * Loads the toggle's own state and the server's mail configuration. Both
   * matter: without an admin address the setting is inert, and a switch that
   * silently does nothing is worse than no switch at all.
   */
  private async chargerReglagesNotification(): Promise<void> {
    await Promise.all([
      this.solverSettings.refresh().catch(() => undefined),
      this.adminApi
        .mailConfig()
        .then((config) => this.adminEmail.set(config.adminEmail))
        .catch(() => this.adminEmail.set(null)),
    ]);
  }

  protected async basculerMailFinResolution(actif: boolean): Promise<void> {
    this.mailFinResolutionBusy.set(true);
    try {
      await this.solverSettings.setMailFinResolution(actif);
      this.notifications.notify({
        title: actif
          ? $localize`:@@parametres.mailFin.active:Notification de fin de résolution activée`
          : $localize`:@@parametres.mailFin.desactive:Notification de fin de résolution désactivée`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.mailFinResolutionBusy.set(false);
    }
  }

  /* ------------------------------ Ninja typologie ---------------------------- */

  /**
   * Warning text when no typologie is flagged ninja (and the referential is
   * not simply empty): without it, no animateur is polyvalent — nobody can be
   * seated outside their own competences, and `preserverBufferPolyvalents`
   * (keep one polyvalent free per créneau to absorb last-minute absences)
   * has nothing to protect. A silent degradation worth a visible sentence.
   */
  protected readonly alerteNinjaManquant = computed(() => {
    if (
      this.store.typologies().length === 0 ||
      this.store.typologies().some((typologie) => typologie.ninja)
    ) {
      return '';
    }
    return $localize`:@@typologies.ninjaManquant:Aucune typologie « ninja » n'est désignée : aucun animateur n'est polyvalent, et la contrainte « préserver un polyvalent libre par créneau » ne protège plus rien.`;
  });

  /** Id of the typologie currently flagged ninja — at most one, `null` when none. */
  protected readonly typologieNinjaId = computed(
    () => this.store.typologies().find((typologie) => typologie.ninja)?.id ?? null,
  );

  /**
   * Promotes `id` as the single ninja typologie, or clears the flag altogether
   * when `id` is `null`. Only the newly selected typologie is sent: the server
   * demotes the previous holder in the same transaction (a partial unique index
   * makes two ninjas impossible anyway).
   */
  protected async setNinja(id: string | null): Promise<void> {
    const label = $localize`:@@typologies.entityLabel:Typologie`;
    const courante = this.store.typologies().find((typologie) => typologie.ninja) ?? null;
    if ((courante?.id ?? null) === id) {
      return;
    }
    if (id === null) {
      if (courante) {
        await this.crud.save('typologies', { ...courante, ninja: false }, courante.id, label);
      }
      return;
    }
    const target = this.store.typologies().find((typologie) => typologie.id === id);
    if (target) {
      await this.crud.save('typologies', { ...target, ninja: true }, target.id, label);
    }
  }

  /* --------------------------- Sauvegarde automatique ------------------------- */

  protected readonly sauvegarde = signal<EtatSauvegarde | null>(null);
  protected readonly sauvegardeBusy = signal(false);

  /**
   * The dumps, newest first, as the server listed them. Kept as a computed so
   * the template never has to guard `sauvegarde()` being null twice.
   */
  protected readonly fichiersSauvegarde = computed(() => this.sauvegarde()?.files ?? []);

  private async chargerSauvegarde(): Promise<void> {
    try {
      this.sauvegarde.set(await this.adminApi.backups());
    } catch {
      // A settings screen that fails to load as a whole because one card could
      // not be read would be a worse outcome than that card staying absent.
      this.sauvegarde.set(null);
    }
  }

  protected async basculerSauvegarde(actif: boolean): Promise<void> {
    this.sauvegardeBusy.set(true);
    try {
      this.sauvegarde.set(await this.adminApi.setBackupsActive(actif));
      this.notifications.notify({
        title: actif
          ? $localize`:@@parametres.sauvegarde.reprise:Sauvegarde automatique réactivée`
          : $localize`:@@parametres.sauvegarde.suspendue:Sauvegarde automatique suspendue`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.sauvegardeBusy.set(false);
    }
  }

  /** Human-sized file length; a dump is megabytes, never bytes worth reading one by one. */
  protected tailleLisible(octets: number): string {
    const mega = octets / (1024 * 1024);
    return mega >= 1 ? `${mega.toFixed(1)} Mo` : `${Math.max(1, Math.round(octets / 1024))} Ko`;
  }

  /* --------------------------------- SQL dump -------------------------------- */

  protected async onExportSql(): Promise<void> {
    this.transferBusy.set(true);
    this.output.set($localize`:@@dataTransfer.buildingSqlDump:Construction du dump SQL...`);
    try {
      this.output.set(await this.adminApi.exportDatabase(this.nomFichierDump));
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.transferBusy.set(false);
    }
  }

  protected pickSqlFile(): void {
    this.sqlInput().nativeElement.click();
  }

  protected async onSqlFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    // Not the edition name here: replaying a dump rewrites the whole database,
    // every edition included, so asking for the current edition's name would
    // describe an operation narrower than the one about to run. A keyword says
    // the truth of the scope instead.
    const confirmed = await this.recopie.demander({
      title: $localize`:@@dataTransfer.replaySqlTitle:Rejouer ce dump SQL ?`,
      message: $localize`:@@dataTransfer.replaySqlPromptMessage:${file.name}:fileName: remplace la base entière : toutes les éditions sont écrasées. L'opération est irréversible.`,
      valeurAttendue: REPLACE_KEYWORD,
      confirmLabel: $localize`:@@dataTransfer.replaySqlAction:Remplacer la base`,
    });
    if (!confirmed) {
      return;
    }
    await this.instantane.proposer($localize`:@@dataSetup.action.importSql:rejouer un dump SQL`);
    this.transferBusy.set(true);
    this.output.set(
      $localize`:@@dataTransfer.importing:Import de ${file.name}:fileName: en cours...`,
    );
    try {
      const summary = await this.adminApi.importDatabase(await file.text());
      await this.scenarioImport.rechargerApresImport();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.transferBusy.set(false);
    }
  }
}

// Reads the picked file and clears the input so the same file can be picked twice.
function takeFile(event: Event): File | null {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  return file;
}
