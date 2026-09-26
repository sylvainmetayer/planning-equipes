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
import { GelEditionNotice } from '../../shared/gel-edition-notice';
import { OutputPanel } from '../../shared/output-panel';
import { defaultExample, describeExample, groupExamples, GroupeExemples } from './exemples';

/**
 * « Exemples »: loads one of the scenarios bundled with the application over
 * the current edition — the demonstration, and the cases a tester wants to
 * replay. Each is offered by a readable name and one sentence (its size, its
 * days, what it is about), sorted « pour découvrir / pour tester un cas /
 * extrêmes »; the file names the server lists never reach the screen
 * ({@link describeExample}).
 *
 * <p>It writes, and destructively: the whole choreography (resolve the target
 * edition, count the impact, confirm, snapshot the plan, reload the stores) is
 * {@link ScenarioImportService}'s, exactly as for a scenario file.</p>
 */
@Component({
  selector: 'app-exemples-card',
  imports: [
    GelEditionNotice,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    OutputPanel,
  ],
  templateUrl: './exemples-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExemplesCard implements OnInit {
  protected readonly output = signal('');
  protected readonly chargement = signal(false);

  /** The bundled scenarios, by family, and the one currently selected (by its file name). */
  protected readonly groupes = signal<GroupeExemples[]>([]);
  protected readonly selectedScenario = signal<string | null>(null);
  protected readonly selected = computed(() => {
    const name = this.selectedScenario();
    return name === null ? null : describeExample(name);
  });

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

  // Preselects the realistic example, so the button always has a target.
  private async loadScenarioList(): Promise<void> {
    try {
      const groupes = groupExamples(await this.planningApi.scenarioNames());
      this.groupes.set(groupes);
      if (!this.selectedScenario()) {
        this.selectedScenario.set(defaultExample(groupes));
      }
    } catch (error) {
      this.output.set(
        $localize`:@@exemples.listError:Liste des exemples illisible : ${errorMessage(error)}:message:`,
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
    const exemple = this.selected();
    if (exemple === null) {
      return;
    }
    const libelle = exemple.libelle;
    this.chargement.set(true);
    this.output.set(
      $localize`:@@exemples.chargement:Chargement de l'exemple « ${libelle}:libelle: »…`,
    );
    try {
      const outcome = await this.scenarioImport.importer({ kind: 'name', name: exemple.name });
      if (outcome.status === 'cancelled') {
        this.output.set('');
        return;
      }
      this.output.set(
        this.scenarioImport.recapitulatif(
          outcome.result,
          $localize`:@@exemples.charge:Exemple « ${libelle}:libelle: » chargé : les référentiels sont remplis.`,
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
