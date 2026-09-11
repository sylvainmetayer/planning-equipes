import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { PlanningApi } from '../../core/api/planning-api';
import { CreneauxApi } from '../../core/api/creneaux-api';
import { BRANDING, slugMarque } from '../../core/branding';
import { EditionStore } from '../../core/edition.store';
import { EtatSauvegarde, ImportScenarioResult, ParametresDecoupage } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { OutputPanel } from '../../shared/output-panel';
import { StatusMessage } from '../../shared/status-message';
import { ParametresNotificationsPanel } from './parametres-notifications';
import { errorMessage, errorPrefix } from '../../core/error-message';

/**
 * Typed back before a SQL dump is replayed. Left untranslated on purpose: a
 * keyword whose spelling follows the interface language is a keyword an
 * administrator gets wrong after a language switch.
 */
export const REPLACE_KEYWORD = 'REMPLACER';

/**
 * The single settings page, split in two sections mirroring the data model:
 *
 * - "Paramètres de l'édition" — everything scoped by the `X-Edition-Id`
 *   partition: scenario imports (they write the current edition only, and the
 *   confirmation names it), the découpage parameters (the generation itself
 *   lives with the créneaux it replaces), the ninja typologie, plus pointers
 *   to the parameters that stay on their own screen (solver duration, foire
 *   aux échanges, constraint toggles).
 * - "Paramètres globaux" — the SQL dump import/export and the automatic
 *   backup: both take the WHOLE database, every edition included, so neither
 *   belongs to any edition.
 *
 * Also runs the solver-free feasibility check on entry and after every import,
 * as the former Données page did: a structurally impossible planning is called
 * out here rather than after a fruitless solve.
 */
@Component({
  selector: 'app-parametres-page',
  imports: [
    DatePipe,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
    MatSlideToggleModule,
    RouterLink,
    FeasibilityBanner,
    OutputPanel,
    ParametresNotificationsPanel,
    StatusMessage,
  ],
  templateUrl: './parametres-page.html',
  styleUrls: ['./parametres.css', '../../../styles/decoupage.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresPage {
  /** Dump named after the deployment, so two instances' exports never collide in a downloads folder. */
  private readonly nomFichierDump = `${slugMarque(inject(BRANDING).productName)}.sql`;

  protected readonly output = signal('');
  protected readonly sampleLoading = signal(false);
  protected readonly exporting = signal(false);
  protected readonly transferBusy = signal(false);
  protected readonly scenarioFileImporting = signal(false);

  /** Scenario files offered by the backend, and the one currently selected. */
  protected readonly scenarios = signal<string[]>([]);
  protected readonly selectedScenario = signal<string | null>(null);

  /** Admin address configured server-side, `null` when mail is disabled entirely. */
  protected readonly adminEmail = signal<string | null>(null);
  protected readonly mailFinResolutionBusy = signal(false);
  protected readonly mailFinResolution = computed(() => this.solverSettings.mailFinResolution());

  /** The server-side solver lock: also covers a solve/analysis from another browser. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());
  /**
   * Edition-scoped writes (scenario import, découpage parameters, ninja
   * typologie) follow the per-edition lock: a solve running on ANOTHER
   * edition leaves them available.
   */
  protected readonly editionLocked = computed(() => this.jobs.editingLocked());
  /** SQL dump replay rewrites the WHOLE database, every edition included: locked by any running job. */
  protected readonly transferLocked = computed(() => this.transferBusy() || this.solverBusy());

  private readonly sqlInput = viewChild.required<ElementRef<HTMLInputElement>>('sqlInput');
  private readonly scenarioFileInput =
    viewChild.required<ElementRef<HTMLInputElement>>('scenarioFileInput');
  /** Pre-solve diagnostic shown by the banner at the top of the page. */
  protected readonly problemes = inject(ProblemesStore);
  protected readonly editions = inject(EditionStore);
  protected readonly store = inject(ReferenceDataStore);

  private readonly adminApi = inject(AdminApi);
  private readonly planningApi = inject(PlanningApi);
  private readonly creneauxApi = inject(CreneauxApi);
  private readonly scenarioImport = inject(ScenarioImportService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  // Seeding or replacing the database moves both the resolution stamp and the
  // "data edited since the last solve" hint: refresh the store the toolbar
  // warnings read, or they keep showing the previous dataset.
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly recopie = inject(ConfirmationRecopie);
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly instantane = inject(InstantaneAvantAction);

  private readonly jobs = inject(SolverJobService);
  protected readonly solverSettings = inject(SolverSettingsService);
  private readonly notifications = inject(NotificationService);

  constructor() {
    void this.loadScenarioList();
    void this.problemes.reloadFeasibility();
    void this.crud.reload();
    void this.chargerParametresDecoupage();
    void this.chargerReglagesNotification();
    void this.chargerSauvegarde();
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

  /* ----------------------------- Scenario import ---------------------------- */

  // Fills the dropdown with the scenario files exposed by the backend. Selects
  // the first one so the "Load" button always has a target.
  private async loadScenarioList(): Promise<void> {
    try {
      const names = await this.planningApi.scenarioNames();
      this.scenarios.set(names);
      if (names.length > 0 && !this.selectedScenario()) {
        this.selectedScenario.set(names[0]);
      }
    } catch (error) {
      this.output.set(
        $localize`:@@dataSetup.scenarioListError:Erreur lors du chargement de la liste des scénarios : ${errorMessage(error)}:message:`,
      );
    }
  }

  protected onSelectScenario(name: string): void {
    this.selectedScenario.set(name);
  }

  protected async onLoadSample(): Promise<void> {
    if (this.solverActionBlocked()) {
      return;
    }
    const name = this.selectedScenario();
    this.sampleLoading.set(true);
    this.output.set(
      name
        ? $localize`:@@dataSetup.loadingScenario:Chargement du scénario « ${name}:name: »...`
        : $localize`:@@dataSetup.loadingSample:Chargement du planning d'exemple...`,
    );
    try {
      const outcome = await this.scenarioImport.importer({ kind: 'name', name });
      if (outcome.status === 'cancelled') {
        this.output.set('');
        return;
      }
      await this.chargerParametresDecoupage();
      this.output.set(
        this.recapImport(
          outcome.result,
          $localize`:@@dataSetup.sampleLoaded:Planning d'exemple chargé : les données de référence sont peuplées.`,
        ),
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.sampleLoading.set(false);
    }
  }

  // Read-only, so it is not gated by solverActionBlocked() like the imports:
  // it never touches the dataset, only reads it.
  protected async onExportScenario(): Promise<void> {
    this.exporting.set(true);
    this.output.set(
      $localize`:@@dataSetup.exportingScenario:Export des données actuelles en fichier scénario...`,
    );
    try {
      const result = await this.planningApi.exportScenario();
      this.output.set(result);
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exporting.set(false);
    }
  }

  protected pickScenarioFile(): void {
    this.scenarioFileInput().nativeElement.click();
  }

  protected async onScenarioFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    const content = await file.text();
    this.scenarioFileImporting.set(true);
    this.output.set(
      $localize`:@@dataSetup.importingScenarioFile:Import de ${file.name}:fileName: en cours...`,
    );
    try {
      const outcome = await this.scenarioImport.importer({
        kind: 'file',
        fileName: file.name,
        content,
      });
      if (outcome.status === 'cancelled') {
        this.output.set('');
        return;
      }
      await this.chargerParametresDecoupage();
      this.output.set(
        this.recapImport(
          outcome.result,
          $localize`:@@dataSetup.scenarioFileImported:Scénario ${file.name}:fileName: importé : les données de référence sont peuplées.`,
        ),
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.scenarioFileImporting.set(false);
    }
  }

  /** Output-panel recap; the cross-edition case additionally raises the {@link proposerBascule} dialog. */
  private recapImport(result: ImportScenarioResult | null, fallback: string): string {
    if (!result?.editionId) {
      return fallback;
    }
    const nom = result.editionNom ?? result.editionId;
    const destination = result.editionCreee
      ? $localize`:@@parametres.recap.editionCreee:Édition « ${nom}:edition: » créée : les données du scénario y ont été importées.`
      : $localize`:@@parametres.recap.editionExistante:Données du scénario importées dans l'édition existante « ${nom}:edition: ».`;
    const courante = this.editions.courant();
    const ailleurs =
      courante && courante.id !== result.editionId
        ? ' ' +
          $localize`:@@parametres.recap.basculer:Vous consultez « ${courante.nom}:courante: » : basculez d'édition (bandeau du haut) pour voir les données importées.`
        : '';
    return destination + ailleurs;
  }

  /* --------------------------- Découpage parameters -------------------------- */

  protected readonly parametresDecoupage = signal<ParametresDecoupage | null>(null);
  protected readonly parametresDecoupageLoading = signal(false);

  /**
   * What the current settings would produce, in one sentence, recomputed as
   * they are typed. The generation itself lives on the Créneaux page: this
   * only answers "am I about to cut 4-hour or 8-hour vacations?" before the
   * user commits to it.
   */
  protected readonly apercuParametres = computed(() => {
    const p = this.parametresDecoupage();
    if (!p) {
      return '';
    }
    const heures = (minutes: number) =>
      (minutes / 60).toFixed(1).replace('.0', '').replace('.', ',');
    const families =
      p.nombreFamillesDecalage > 1
        ? $localize`:@@decoupage.apercu.familles:, réparties sur ${p.nombreFamillesDecalage}:count: grilles décalées`
        : '';
    return $localize`:@@decoupage.apercu:Avec ces réglages : des vacations d'environ ${heures(p.dureeVacationCibleMinutes)}:cible: h (jamais plus de ${heures(p.dureeVacationMaxMinutes)}:max: h), un relais de ${p.dureeChevauchementMinutes}:chevauchement: min et une pause repas de ${p.dureePauseRepasMinutes}:repas: min${families}:familles:.`;
  });

  /**
   * Immutable field update: the « Avec ces réglages » preview is a computed
   * over the `parametresDecoupage` signal, and a zoneless app never notices
   * an in-place mutation. Replacing the object is what makes it live while
   * typing.
   */
  protected patchParametre(patch: Partial<ParametresDecoupage>): void {
    this.parametresDecoupage.update((p) => (p ? { ...p, ...patch } : p));
  }

  private async chargerParametresDecoupage(): Promise<void> {
    this.parametresDecoupageLoading.set(true);
    try {
      this.parametresDecoupage.set(await this.creneauxApi.slicingParameters());
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.parametresDecoupageLoading.set(false);
    }
  }

  protected async sauvegarderParametresDecoupage(): Promise<void> {
    const parametres = this.parametresDecoupage();
    if (!parametres) {
      return;
    }
    this.parametresDecoupageLoading.set(true);
    try {
      this.parametresDecoupage.set(await this.creneauxApi.saveSlicingParameters(parametres));
      this.notifications.notify({
        title: $localize`:@@decoupage.parametresSaved:Paramètres de découpage enregistrés.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.parametresDecoupageLoading.set(false);
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
      await this.chargerParametresDecoupage();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.transferBusy.set(false);
    }
  }

  // Guards against a race: the buttons are disabled while a solver job runs
  // on this edition, but a job could have started between the last render and
  // the click.
  private solverActionBlocked(): boolean {
    if (this.editionLocked()) {
      const description = this.jobs.activeJobDescription();
      this.output.set(
        $localize`:@@dataSetup.lockedByJob:${description}:description: La configuration des données est verrouillée jusqu'à la fin.`,
      );
      return true;
    }
    return false;
  }

  // A seed or bulk import invalidates whatever planning was displayed, and
  // moves both the resolution stamp and the "data edited since the last
  // solve" stamp the toolbar warnings are computed from. A scenario may also
  // have pinned its own solver duration or découpage parameters (see
  // import-scenario), so those are refreshed too — harmless when unchanged.
  // The feasibility diagnostic is recomputed from the new dataset for the
  // same reason: it is about to drive the decision to launch a solve.
}

// Reads the picked file and clears the input so the same file can be picked twice.
function takeFile(event: Event): File | null {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  return file;
}
