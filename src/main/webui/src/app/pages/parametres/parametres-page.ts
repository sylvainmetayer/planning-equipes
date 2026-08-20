import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { EditionStore } from '../../core/edition.store';
import { CibleImport, Edition, ImpactImport, ImportSummary, ImportScenarioResult, ParametresDecoupage } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { PlanningResolutionStore } from '../../core/planning-resolution.store';
import { PlanningStateService } from '../../core/planning-state.service';
import { ProblemesStore } from '../../core/problemes.store';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { SolverSettingsService } from '../../core/solver-settings.service';
import { PlanSnapshotStore } from '../../core/plan-snapshot.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { OutputPanel } from '../../shared/output-panel';
import { StatusMessage } from '../../shared/status-message';

/**
 * The single settings page, split in two sections mirroring the data model:
 *
 * - "Paramètres de l'édition" — everything scoped by the `X-Edition-Id`
 *   partition: scenario imports (they write the current edition only, and the
 *   confirmation names it), the découpage parameters (the generation itself
 *   lives with the créneaux it replaces), the ninja typologie, plus pointers
 *   to the parameters that stay on their own screen (solver duration, foire
 *   aux échanges, constraint toggles).
 * - "Paramètres globaux" — the SQL dump import/export: it replays the WHOLE
 *   database, every edition included, so it does not belong to any edition.
 *
 * Also runs the solver-free feasibility check on entry and after every import,
 * as the former Données page did: a structurally impossible planning is called
 * out here rather than after a fruitless solve.
 */
@Component({
  selector: 'app-parametres-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    RouterLink,
    FeasibilityBanner,
    OutputPanel,
    StatusMessage
  ],
  templateUrl: './parametres-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ParametresPage {
  protected readonly output = signal('');
  protected readonly sampleLoading = signal(false);
  protected readonly exporting = signal(false);
  protected readonly transferBusy = signal(false);
  protected readonly scenarioFileImporting = signal(false);

  /** Scenario files offered by the backend, and the one currently selected. */
  protected readonly scenarios = signal<string[]>([]);
  protected readonly selectedScenario = signal<string | null>(null);

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
  private readonly scenarioFileInput = viewChild.required<ElementRef<HTMLInputElement>>('scenarioFileInput');
  /** Pre-solve diagnostic shown by the banner at the top of the page. */
  protected readonly problemes = inject(ProblemesStore);
  protected readonly editions = inject(EditionStore);
  protected readonly store = inject(ReferenceDataStore);

  private readonly api = inject(ApiService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  // Seeding or replacing the database moves both the resolution stamp and the
  // "data edited since the last solve" hint: refresh the store the toolbar
  // warnings read, or they keep showing the previous dataset.
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly confirm = inject(ConfirmService);
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly instantane = inject(InstantaneAvantAction);

  private readonly jobs = inject(SolverJobService);
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly notifications = inject(NotificationService);

  constructor() {
    void this.loadScenarioList();
    void this.problemes.reloadFeasibility();
    void this.crud.reload();
    void this.chargerParametresDecoupage();
  }

  /* ----------------------------- Scenario import ---------------------------- */

  // Fills the dropdown with the scenario files exposed by the backend. Selects
  // the first one so the "Load" button always has a target.
  private async loadScenarioList(): Promise<void> {
    try {
      const names = await this.api.get<string[]>('/api/planning/scenarios');
      this.scenarios.set(names);
      if (names.length > 0 && !this.selectedScenario()) {
        this.selectedScenario.set(names[0]);
      }
    } catch (error) {
      this.output.set(
        $localize`:@@dataSetup.scenarioListError:Erreur lors du chargement de la liste des scénarios : ${message(error)}:message:`
      );
    }
  }

  protected onSelectScenario(name: string): void {
    this.selectedScenario.set(name);
  }

  /**
   * The gate of both scenario-import buttons: names the edition the import
   * will write into (a scenario import only ever touches the current one —
   * the operator confirms the target, not just the action), then what it
   * will replace or erase — counted server-side — and, once confirmed, saves
   * an automatic snapshot of the resolved plan (when there is one) so the
   * operation stays reversible on the planning side. `false` aborts.
   */
  private async confirmerImportScenario(intitule: string, cible: CibleImport): Promise<boolean> {
    const courante = this.editions.courant()?.nom ?? '';
    const lignes: string[] = [];
    // Which edition the impact must be counted on, and whether the automatic
    // snapshot makes sense (it protects the CURRENT edition's plan only).
    let editionImpact: string | null = null;
    let importDansEditionCourante = true;
    let compterImpact = true;
    if (!cible.editionId) {
      lignes.push($localize`:@@parametres.impact.edition:L'import écrit dans l'édition « ${courante}:edition: », et elle seule.`);
    } else if (!cible.existe) {
      const nom = cible.editionNomFichier ?? cible.editionId;
      lignes.push($localize`:@@parametres.cible.creation:Ce fichier désigne l'édition « ${nom}:cible: » : elle sera CRÉÉE et recevra l'import — votre édition actuelle « ${courante}:courante: » ne sera pas modifiée.`);
      importDansEditionCourante = false;
      compterImpact = false;
    } else if (cible.editionId === this.editions.courant()?.id) {
      lignes.push($localize`:@@parametres.cible.courante:Ce fichier désigne l'édition « ${courante}:edition: » — votre édition actuelle : l'import y écrit, et dans elle seule.`);
    } else {
      const nom = cible.editionNomExistant ?? cible.editionId;
      lignes.push($localize`:@@parametres.cible.existante:Ce fichier désigne l'édition existante « ${nom}:cible: » : l'import remplacera SES données — votre édition actuelle « ${courante}:courante: » ne sera pas modifiée.`);
      editionImpact = cible.editionId;
      importDansEditionCourante = false;
    }
    let impact: ImpactImport | null = null;
    if (compterImpact) {
      try {
        impact = editionImpact
          ? await this.api.getDansEdition<ImpactImport>('/api/reference-data/impact-import', editionImpact)
          : await this.api.get<ImpactImport>('/api/reference-data/impact-import');
      } catch {
        // Counting is comfort, not safety: without it the dialog still warns.
      }
    }
    lignes.push(messageImpactImport(impact, intitule, importDansEditionCourante));
    const confirme = await this.confirm.ask({
      title: $localize`:@@dataSetup.impact.titre:Importer et remplacer les données ?`,
      message: lignes.join(' '),
      confirmLabel: $localize`:@@dataTransfer.importAction:Importer`,
      danger: true
    });
    if (!confirme) {
      return false;
    }
    if (impact?.planningResolu && importDansEditionCourante) {
      try {
        await this.snapshots.capturer(
          $localize`:@@dataSetup.snapshotBefore.libelle:Avant ${intitule}:action:`
        );
        this.notifications.notify({
          title: $localize`:@@dataSetup.impact.instantane:Instantané du plan enregistré avant l'import.`,
          variant: 'info'
        });
      } catch (error) {
        this.notifications.notify({
          title: $localize`:@@dataSetup.snapshotBefore.failed:Instantané non enregistré`,
          message: message(error),
          variant: 'error'
        });
      }
    }
    return true;
  }

  /**
   * After an import routed by the file's `edition:` section into ANOTHER
   * edition than the one this browser shows: an unmissable dialog says what
   * happened and offers to switch right away — a passing notification proved
   * too easy to miss, leaving the operator staring at an unchanged screen.
   */
  private async proposerBascule(result: ImportScenarioResult | null): Promise<void> {
    if (!result?.editionId || result.editionId === this.editions.courant()?.id) {
      return;
    }
    const nom = result.editionNom ?? result.editionId;
    const courante = this.editions.courant()?.nom ?? '';
    const destination = result.editionCreee
      ? $localize`:@@parametres.recap.editionCreee:Édition « ${nom}:edition: » créée : les données du scénario y ont été importées.`
      : $localize`:@@parametres.recap.editionExistante:Données du scénario importées dans l'édition existante « ${nom}:edition: ».`;
    const basculer = await this.confirm.ask({
      title: $localize`:@@parametres.recap.titre:Import dans une édition désignée par le fichier`,
      message: destination + ' ' + $localize`:@@parametres.recap.question:Voulez-vous basculer dessus maintenant ? (La page se recharge.)`,
      confirmLabel: $localize`:@@parametres.recap.oui:Basculer sur « ${nom}:edition: »`,
      cancelLabel: $localize`:@@parametres.recap.non:Rester sur « ${courante}:courante: »`
    });
    if (basculer) {
      this.editions.basculer({ id: result.editionId } as Edition);
    }
  }

  protected async onLoadSample(): Promise<void> {
    if (this.solverActionBlocked()) {
      return;
    }
    const name = this.selectedScenario();
    let cible: CibleImport;
    try {
      cible = await this.api.get<CibleImport>(
        `/api/reference-data/cible-scenario?name=${encodeURIComponent(name ?? '')}`);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
      return;
    }
    if (!(await this.confirmerImportScenario(
      $localize`:@@dataSetup.action.importScenario:charger un scénario`, cible))) {
      return;
    }
    this.sampleLoading.set(true);
    this.output.set(
      name
        ? $localize`:@@dataSetup.loadingScenario:Chargement du scénario « ${name}:name: »...`
        : $localize`:@@dataSetup.loadingSample:Chargement du planning d'exemple...`
    );
    try {
      // The scenario is parsed and imported entirely server-side: we only send
      // its name, so a large scenario never travels to the browser and back.
      const url = name
        ? `/api/reference-data/import-scenario?name=${encodeURIComponent(name)}`
        : '/api/reference-data/import-scenario';
      const result = await this.api.post<ImportScenarioResult | null>(url, {});
      await this.refreshAfterImport();
      await this.proposerBascule(result);
      this.output.set(this.recapImport(result,
        $localize`:@@dataSetup.sampleLoaded:Planning d'exemple chargé. Les données de référence sont peuplées et modifiables depuis les pages de référence.`
      ));
      this.notifyDecoupageAuto(result);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
    } finally {
      this.sampleLoading.set(false);
    }
  }

  // Read-only, so it is not gated by solverActionBlocked() like the imports:
  // it never touches the dataset, only reads it.
  protected async onExportScenario(): Promise<void> {
    this.exporting.set(true);
    this.output.set($localize`:@@dataSetup.exportingScenario:Export des données actuelles en fichier scénario...`);
    try {
      const result = await this.api.downloadGet('/api/planning/export-scenario', 'scenario.yaml', 'application/x-yaml');
      this.output.set(result);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
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
    const contenu = await file.text();
    let cible: CibleImport;
    try {
      cible = await this.api.postRaw<CibleImport>(
        '/api/reference-data/cible-scenario-fichier', contenu, 'application/x-yaml');
    } catch (error) {
      const errorMessage = message(error);
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${errorMessage}:message:`);
      this.notifications.notify({
        title: $localize`:@@dataSetup.importScenarioFileInvalid:Fichier scénario invalide`,
        message: errorMessage,
        variant: 'error'
      });
      return;
    }
    if (!(await this.confirmerImportScenario(
      $localize`:@@dataSetup.action.importScenarioFichier:importer un fichier scénario`, cible))) {
      return;
    }
    this.scenarioFileImporting.set(true);
    this.output.set($localize`:@@dataSetup.importingScenarioFile:Import de ${file.name}:fileName: en cours...`);
    try {
      const result = await this.api.postRaw<ImportScenarioResult | null>(
        '/api/reference-data/import-scenario-fichier',
        contenu,
        'application/x-yaml'
      );
      await this.refreshAfterImport();
      await this.proposerBascule(result);
      this.output.set(this.recapImport(result,
        $localize`:@@dataSetup.scenarioFileImported:Scénario ${file.name}:fileName: importé. Les données de référence sont peuplées et modifiables depuis les pages de référence.`
      ));
      this.notifyDecoupageAuto(result);
    } catch (error) {
      const errorMessage = message(error);
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${errorMessage}:message:`);
      this.notifications.notify({
        title: $localize`:@@dataSetup.importScenarioFileInvalid:Fichier scénario invalide`,
        message: errorMessage,
        variant: 'error'
      });
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
    const ailleurs = courante && courante.id !== result.editionId
      ? ' ' + $localize`:@@parametres.recap.basculer:Vous consultez actuellement « ${courante.nom}:courante: » : basculez d'édition (bandeau en haut de l'écran) pour voir les données importées.`
      : '';
    return destination + ailleurs;
  }

  // Surfaces the scenario's decoupageAuto section, when present: the import
  // replaced the file's amplitudes with the generated vacations in place, so
  // the operator is told without having to open the Créneaux page.
  private notifyDecoupageAuto(result: ImportScenarioResult | null): void {
    if (!result?.decoupageAuto) {
      return;
    }
    this.notifications.notify({
      title: $localize`:@@dataSetup.decoupageAuto.applied:Découpage automatique appliqué`,
      message: $localize`:@@dataSetup.decoupageAuto.appliedHint:Les amplitudes du scénario ont été découpées : l'édition porte désormais les vacations générées.`,
      variant: 'info'
    });
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
    const heures = (minutes: number) => (minutes / 60).toFixed(1).replace('.0', '').replace('.', ',');
    const familles =
      p.nombreFamillesDecalage > 1
        ? $localize`:@@decoupage.apercu.familles:, réparties sur ${p.nombreFamillesDecalage}:count: grilles décalées`
        : '';
    return $localize`:@@decoupage.apercu:Avec ces réglages : des vacations d'environ ${heures(p.dureeVacationCibleMinutes)}:cible: h (jamais plus de ${heures(p.dureeVacationMaxMinutes)}:max: h), un relais de ${p.dureeChevauchementMinutes}:chevauchement: min et une pause repas de ${p.dureePauseRepasMinutes}:repas: min${familles}:familles:.`;
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
      this.parametresDecoupage.set(await this.api.get<ParametresDecoupage>('/api/parametres-decoupage'));
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
      this.parametresDecoupage.set(await this.api.put<ParametresDecoupage>('/api/parametres-decoupage', parametres));
      this.notifications.notify({
        title: $localize`:@@decoupage.parametresSaved:Paramètres de découpage enregistrés.`,
        variant: 'success',
        timeout: 4000
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
    if (this.store.typologies().length === 0 || this.store.typologies().some((typologie) => typologie.ninja)) {
      return '';
    }
    return $localize`:@@typologies.ninjaManquant:Aucune typologie « ninja » n'est désignée. Sans elle, aucun animateur n'est polyvalent : personne ne peut être affecté en dehors de ses compétences, et la contrainte « préserver un polyvalent libre par créneau » (votre marge de manœuvre en cas d'absence de dernière minute) ne protège plus rien. Choisissez la typologie qui joue ce rôle dans le sélecteur ci-dessous.`;
  });

  /** Id of the typologie currently flagged ninja — at most one, `null` when none. */
  protected readonly typologieNinjaId = computed(
    () => this.store.typologies().find((typologie) => typologie.ninja)?.id ?? null
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
    const cible = this.store.typologies().find((typologie) => typologie.id === id);
    if (cible) {
      await this.crud.save('typologies', { ...cible, ninja: true }, cible.id, label);
    }
  }

  /* --------------------------------- SQL dump -------------------------------- */

  protected async onExportSql(): Promise<void> {
    this.transferBusy.set(true);
    this.output.set($localize`:@@dataTransfer.buildingSqlDump:Construction du dump SQL...`);
    try {
      this.output.set(await this.api.downloadGet('/api/database/export', 'planning-equipes.sql', 'application/sql'));
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
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
    const confirmed = await this.confirm.ask({
      title: $localize`:@@dataTransfer.replaySqlTitle:Rejouer ce dump SQL ?`,
      message: $localize`:@@dataTransfer.replaySqlMessage:${file.name}:fileName: remplace le contenu actuel de la base de données.`,
      confirmLabel: $localize`:@@dataTransfer.importAction:Importer`,
      danger: true
    });
    if (!confirmed) {
      return;
    }
    await this.instantane.proposer($localize`:@@dataSetup.action.importSql:rejouer un dump SQL`);
    this.transferBusy.set(true);
    this.output.set($localize`:@@dataTransfer.importing:Import de ${file.name}:fileName: en cours...`);
    try {
      const summary = await this.api.postRaw<ImportSummary>(
        '/api/database/import',
        await file.text(),
        'application/sql'
      );
      await this.refreshAfterImport();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set($localize`:@@common.errorPrefix:Erreur : ${message(error)}:message:`);
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
        $localize`:@@dataSetup.lockedByJob:${description}:description: La configuration des données est verrouillée jusqu'à la fin.`
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
  private async refreshAfterImport(): Promise<void> {
    this.planningState.set(null);
    await Promise.all([
      this.referenceData.reload(),
      this.resolution.reload(),
      this.solverSettings.refresh(),
      this.problemes.reloadFeasibility(),
      this.chargerParametresDecoupage(),
      // An `edition:` scenario section may just have created an edition: the
      // switcher in the shell must list it right away.
      this.editions.reload()
    ]);
  }
}

/**
 * The confirmation message of a scenario import, built from the server-side
 * impact counts: what gets replaced, what disappears with it, what stays.
 * With `impact` null (the counting call failed), a generic warning remains —
 * counting is comfort, never the safety net itself.
 */
export function messageImpactImport(impact: ImpactImport | null, intitule: string, avecInstantane = true): string {
  const lignes: string[] = [
    $localize`:@@dataSetup.impact.base:Cette action va ${intitule}:action: : les stands et animateurs sont remplacés par ceux du fichier, et ceux qui n'y figurent pas sont supprimés — avec leurs demandes d'échange, sessions et codes d'accès. Les animateurs conservés gardent leur lien d'espace et leur e-mail.`
  ];
  if (impact) {
    lignes.push(
      $localize`:@@dataSetup.impact.referentiel:Actuellement : ${impact.animateurs}:animateurs: animateur(s) et ${impact.stands}:stands: stand(s).`
    );
    if (impact.planningResolu) {
      lignes.push(
        avecInstantane
          ? $localize`:@@dataSetup.impact.planning:Le planning résolu (${impact.postes}:postes: affectation(s)) sera effacé, ainsi que ${impact.verrous}:verrous: verrouillage(s) ; un instantané sera enregistré automatiquement avant l'import.`
          : $localize`:@@dataSetup.impact.planningSansInstantane:Le planning résolu de cette édition (${impact.postes}:postes: affectation(s)) sera effacé, ainsi que ${impact.verrous}:verrous: verrouillage(s).`
      );
    }
    if (impact.demandesEchange > 0) {
      lignes.push(
        $localize`:@@dataSetup.impact.demandes:${impact.demandesEchange}:demandes: demande(s) d'échange (dont ${impact.demandesEnAttente}:enAttente: en attente) seront perdues si leurs créneaux sont remplacés ou leurs animateurs supprimés.`
      );
    }
  }
  return lignes.join(' ');
}

// Reads the picked file and clears the input so the same file can be picked twice.
function takeFile(event: Event): File | null {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  return file;
}

function message(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
