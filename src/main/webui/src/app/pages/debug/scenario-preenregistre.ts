import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { PlanningApi } from '../../core/api/planning-api';
import { errorMessage, errorPrefix } from '../../core/error-message';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { OutputPanel } from '../../shared/output-panel';

/**
 * Loads one of the scenarios bundled with the application over the current
 * edition. A diagnostic tool, and shown as one: the list is the fifty-odd files
 * of `src/main/resources/scenarios` — the hand-written demo fixtures, the
 * thirty rungs of the ladder and the fifteen extreme cases — which is a
 * developer's and a support engineer's catalogue, not an organiser's.
 *
 * <p>It writes, and destructively: the whole choreography (resolve the target
 * edition, count the impact, confirm, snapshot the plan, reload the stores) is
 * {@link ScenarioImportService}'s, exactly as for the file upload that now
 * lives on the Imports screen.</p>
 */
@Component({
  selector: 'app-scenario-preenregistre',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    OutputPanel,
  ],
  templateUrl: './scenario-preenregistre.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ScenarioPreenregistre implements OnInit {
  protected readonly output = signal('');
  protected readonly chargement = signal(false);

  /** Scenario files offered by the backend, and the one currently selected. */
  protected readonly scenarios = signal<string[]>([]);
  protected readonly selectedScenario = signal<string | null>(null);

  /**
   * The per-edition lock: a solve running on ANOTHER edition leaves the import
   * available, since it writes this edition and this one only.
   */
  protected readonly editionLocked = computed(() => this.jobs.editingLocked());

  private readonly planningApi = inject(PlanningApi);
  private readonly scenarioImport = inject(ScenarioImportService);
  private readonly jobs = inject(SolverJobService);

  ngOnInit(): void {
    void this.loadScenarioList();
  }

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
    this.chargement.set(true);
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
      this.output.set(
        this.scenarioImport.recapitulatif(
          outcome.result,
          $localize`:@@dataSetup.sampleLoaded:Planning d'exemple chargé : les données de référence sont peuplées.`,
        ),
      );
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.chargement.set(false);
    }
  }

  // Guards against a race: the button is disabled while a solver job runs on
  // this edition, but a job could have started between the last render and the
  // click.
  private solverActionBlocked(): boolean {
    if (this.editionLocked()) {
      this.output.set(
        $localize`:@@dataSetup.lockedByJob:${this.jobs.activeJobDescription()}:description: La configuration des données est verrouillée jusqu'à la fin.`,
      );
      return true;
    }
    return false;
  }
}
