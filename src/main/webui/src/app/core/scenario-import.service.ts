// Importing a scenario, as one named operation.
//
// This is the most destructive thing the application does: it replaces stands
// and animateurs, erases the solved planning and its locks, and may create or
// overwrite a whole edition. It is also a ten-step choreography — resolve where
// the file routes, count the impact, confirm, snapshot the plan, import,
// reload six stores, offer to switch edition — and
// that choreography used to exist three times, copy-pasted into the settings
// page with two URLs and two labels of difference. Any change of import policy
// ("snapshot in this case too", "stop offering the switch when…") had to be
// applied three times, and nothing checked that it was.
//
// It lives here so it is written once and can be tested without rendering a
// component, exactly like `ReferenceCrudService` does for the CRUD of the five
// reference pages.

import { Injectable, inject } from '@angular/core';
import { ApiService } from './api.service';
import { EditionStore } from './edition.store';
import { errorMessage } from './error-message';
import { messageImpactImport } from './impact-import';
import { NotificationService } from './notification.service';
import { PlanSnapshotStore } from './plan-snapshot.store';
import { PlanningResolutionStore } from './planning-resolution.store';
import { PlanningStateService } from './planning-state.service';
import { ProblemesStore } from './problemes.store';
import { ReferenceDataStore } from './reference-data.store';
import { SolverSettingsService } from './solver-settings.service';
import { ConfirmService } from '../shared/confirm-dialog';
import { CibleImport, Edition, ImpactImport, ImportScenarioResult } from './models';

/**
 * Where the scenario comes from. A name is resolved and parsed entirely
 * server-side, so a large scenario never travels to the browser and back; a
 * file is uploaded as raw YAML.
 */
export type ScenarioSource =
  | { readonly kind: 'name'; readonly name: string | null }
  | { readonly kind: 'file'; readonly fileName: string; readonly content: string };

export interface ScenarioImportOutcome {
  /** `cancelled` when the operator declined the confirmation — not a failure. */
  readonly status: 'imported' | 'cancelled';
  /** The server's answer, for the caller's own recap; null when nothing was imported. */
  readonly result: ImportScenarioResult | null;
}

@Injectable({ providedIn: 'root' })
export class ScenarioImportService {
  private readonly api = inject(ApiService);
  private readonly confirm = inject(ConfirmService);
  private readonly snapshots = inject(PlanSnapshotStore);
  private readonly notifications = inject(NotificationService);
  private readonly editions = inject(EditionStore);
  private readonly planningState = inject(PlanningStateService);
  private readonly referenceData = inject(ReferenceDataStore);
  private readonly resolution = inject(PlanningResolutionStore);
  private readonly solverSettings = inject(SolverSettingsService);
  private readonly problemes = inject(ProblemesStore);

  /**
   * Runs the whole import. Throws whatever the server threw — the caller owns
   * the result panel and its wording; a declined confirmation is reported as
   * `cancelled` rather than as an error, because nothing went wrong.
   */
  async importer(source: ScenarioSource): Promise<ScenarioImportOutcome> {
    const target = await this.resoudreCible(source);
    if (!(await this.confirmerImport(source, target))) {
      return { status: 'cancelled', result: null };
    }
    const result = await this.lancerImport(source);
    await this.rechargerApresImport();
    await this.proposerBascule(result);
    return { status: 'imported', result };
  }

  /** Which edition the file routes to — the server reads its `edition:` section. */
  private async resoudreCible(source: ScenarioSource): Promise<CibleImport> {
    if (source.kind === 'name') {
      return this.api.get<CibleImport>(
        `/api/reference-data/cible-scenario?name=${encodeURIComponent(source.name ?? '')}`,
      );
    }
    try {
      return await this.api.postRaw<CibleImport>(
        '/api/reference-data/cible-scenario-fichier',
        source.content,
        'application/x-yaml',
      );
    } catch (error) {
      // Only for a file: the operator picked this artifact, so an unreadable
      // one deserves a snack bar and not just a line in the result panel.
      this.signalerFichierInvalide(error);
      throw error;
    }
  }

  private async lancerImport(source: ScenarioSource): Promise<ImportScenarioResult | null> {
    if (source.kind === 'name') {
      const url = source.name
        ? `/api/reference-data/import-scenario?name=${encodeURIComponent(source.name)}`
        : '/api/reference-data/import-scenario';
      return this.api.post<ImportScenarioResult | null>(url, {});
    }
    try {
      return await this.api.postRaw<ImportScenarioResult | null>(
        '/api/reference-data/import-scenario-fichier',
        source.content,
        'application/x-yaml',
      );
    } catch (error) {
      this.signalerFichierInvalide(error);
      throw error;
    }
  }

  private signalerFichierInvalide(error: unknown): void {
    this.notifications.notify({
      title: $localize`:@@dataSetup.importScenarioFileInvalid:Fichier scénario invalide`,
      message: errorMessage(error),
      variant: 'error',
    });
  }

  /**
   * Spells out what is about to be replaced and where, then — only once the
   * operator has said yes — snapshots the plan the import is about to destroy,
   * so the operation stays reversible on the planning side. `false` aborts.
   */
  private async confirmerImport(source: ScenarioSource, target: CibleImport): Promise<boolean> {
    const intitule = this.intitule(source);
    const courante = this.editions.courant()?.nom ?? '';
    const lignes: string[] = [];
    // Which edition the impact must be counted on, and whether the automatic
    // snapshot makes sense (it protects the CURRENT edition's plan only).
    let editionImpact: string | null = null;
    let importDansEditionCourante = true;
    let compterImpact = true;
    if (!target.editionId) {
      lignes.push(
        $localize`:@@parametres.impact.edition:L'import écrit dans l'édition « ${courante}:edition: », et elle seule.`,
      );
    } else if (!target.existe) {
      const nom = target.editionNomFichier ?? target.editionId;
      lignes.push(
        $localize`:@@parametres.cible.creation:Ce fichier désigne l'édition « ${nom}:cible: » : elle sera CRÉÉE et recevra l'import — votre édition actuelle « ${courante}:courante: » ne sera pas modifiée.`,
      );
      importDansEditionCourante = false;
      compterImpact = false;
    } else if (target.editionId === this.editions.courant()?.id) {
      lignes.push(
        $localize`:@@parametres.cible.courante:Ce fichier désigne l'édition « ${courante}:edition: » — votre édition actuelle : l'import y écrit, et dans elle seule.`,
      );
    } else {
      const nom = target.editionNomExistant ?? target.editionId;
      lignes.push(
        $localize`:@@parametres.cible.existante:Ce fichier désigne l'édition existante « ${nom}:cible: » : l'import remplacera SES données — votre édition actuelle « ${courante}:courante: » ne sera pas modifiée.`,
      );
      editionImpact = target.editionId;
      importDansEditionCourante = false;
    }
    let impact: ImpactImport | null = null;
    if (compterImpact) {
      try {
        impact = editionImpact
          ? await this.api.getDansEdition<ImpactImport>(
              '/api/reference-data/impact-import',
              editionImpact,
            )
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
      danger: true,
    });
    if (!confirme) {
      return false;
    }
    if (impact?.planningResolu && importDansEditionCourante) {
      await this.capturerInstantane(intitule);
    }
    return true;
  }

  /** A failed snapshot must not silently cancel the import the operator asked for. */
  private async capturerInstantane(intitule: string): Promise<void> {
    try {
      await this.snapshots.capturer(
        $localize`:@@dataSetup.snapshotBefore.libelle:Avant ${intitule}:action:`,
      );
      this.notifications.notify({
        title: $localize`:@@dataSetup.impact.instantane:Instantané du plan enregistré avant l'import.`,
        variant: 'info',
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@dataSetup.snapshotBefore.failed:Instantané non enregistré`,
        message: errorMessage(error),
        variant: 'error',
      });
    }
  }

  private intitule(source: ScenarioSource): string {
    return source.kind === 'name'
      ? $localize`:@@dataSetup.action.importScenario:charger un scénario`
      : $localize`:@@dataSetup.action.importScenarioFichier:importer un fichier scénario`;
  }

  /**
   * Everything an import invalidated. Public because the SQL dump replay —
   * a different operation, but one that replaces the same data — needs exactly
   * the same set.
   */
  async rechargerApresImport(): Promise<void> {
    this.planningState.set(null);
    await Promise.all([
      this.referenceData.reload(),
      this.resolution.reload(),
      this.solverSettings.refresh(),
      this.problemes.reloadFeasibility(),
      // An `edition:` scenario section may just have created an edition: the
      // switcher in the shell must list it right away.
      this.editions.reload(),
    ]);
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
      message:
        destination +
        ' ' +
        $localize`:@@parametres.recap.question:Voulez-vous basculer dessus maintenant ? (La page se recharge.)`,
      confirmLabel: $localize`:@@parametres.recap.oui:Basculer sur « ${nom}:edition: »`,
      cancelLabel: $localize`:@@parametres.recap.non:Rester sur « ${courante}:courante: »`,
    });
    if (basculer) {
      this.editions.basculer({ id: result.editionId } as Edition);
    }
  }

  /**
   * The line the caller's own panel shows once the import went through. Here
   * rather than on a page because the two imports now live on two screens —
   * the pre-recorded scenarios under Débogage, the file upload under Imports —
   * and a recap that differs between them would describe the same operation
   * twice, differently.
   */
  recapitulatif(result: ImportScenarioResult | null, fallback: string): string {
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
}
