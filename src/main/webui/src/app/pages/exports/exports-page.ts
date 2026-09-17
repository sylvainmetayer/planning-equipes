import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { ExportCsvApi } from '../../core/api/export-csv-api';
import { CIBLES_EXPORT_CSV } from '../../core/api/imports-api';
import { PlanningApi } from '../../core/api/planning-api';
import { errorMessage, errorPrefix } from '../../core/error-message';
import { ExportCsvTarget, VolumesExportCsv } from '../../core/models';

/** One referential of the archive: what it is called, and how many rows it holds. */
interface LigneExport {
  target: ExportCsvTarget;
  libelle: string;
  fichier: string;
  total: number;
}

/**
 * « Export »: the <b>data</b> the current edition can write of itself, facing
 * the import screen that reads it back. The planning itself leaves through
 * « Publication » (issue #320), which is a different act on a different
 * audience: this screen writes files for the team, that one mails schedules to
 * people.
 *
 * <p>Two shapes, two uses. The CSV archive writes the referentials out in the
 * very form the import tabs read, each one to tick: a team copying its stands
 * from one year to the next does not necessarily take its animateurs along,
 * and a team replaying its calendar takes only the timeslots and the day
 * templates. The scenario file carries the whole edition at once — which is
 * what the imports' « Scénario » tab reads back.</p>
 */
@Component({
  selector: 'app-exports-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './exports-page.html',
  styleUrl: '../../../styles/import-animateurs.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the import screens it mirrors.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExportsPage {
  private readonly api = inject(ExportCsvApi);
  private readonly planningApi = inject(PlanningApi);

  private readonly volumes = signal<VolumesExportCsv | null>(null);
  protected readonly chargement = signal(true);
  protected readonly telechargement = signal(false);
  protected readonly erreur = signal('');
  protected readonly message = signal('');

  /** Everything is checked to begin with: copying a whole edition is the ordinary case. */
  private readonly choisis = signal<ReadonlySet<ExportCsvTarget>>(
    new Set<ExportCsvTarget>(CIBLES_EXPORT_CSV),
  );

  protected readonly lignes = computed<LigneExport[]>(() => {
    const volumes = this.volumes() ?? {};
    return [
      {
        target: 'TYPOLOGIES' as const,
        libelle: $localize`:@@nav.link.typologies:Typologies`,
        fichier: 'typologies.csv',
        total: volumes.TYPOLOGIES ?? 0,
      },
      {
        target: 'EMPLACEMENTS' as const,
        libelle: $localize`:@@nav.link.emplacements:Emplacements`,
        fichier: 'emplacements.csv',
        total: volumes.EMPLACEMENTS ?? 0,
      },
      {
        target: 'STANDS' as const,
        libelle: $localize`:@@nav.link.stands:Stands`,
        fichier: 'stands.csv',
        total: volumes.STANDS ?? 0,
      },
      {
        target: 'CRENEAUX' as const,
        libelle: $localize`:@@nav.link.creneaux:Créneaux`,
        fichier: 'creneaux.csv',
        total: volumes.CRENEAUX ?? 0,
      },
      {
        target: 'JOURNEES_TYPES' as const,
        libelle: $localize`:@@journeesTypes.title:Journées types`,
        fichier: 'journees-types.csv',
        total: volumes.JOURNEES_TYPES ?? 0,
      },
      {
        target: 'ANIMATEURS' as const,
        libelle: $localize`:@@nav.link.animateurs:Animateurs`,
        fichier: 'animateurs.csv',
        total: volumes.ANIMATEURS ?? 0,
      },
    ];
  });

  protected readonly total = computed(() =>
    this.lignes()
      .filter((ligne) => this.isSelected(ligne.target))
      .reduce((somme, ligne) => somme + ligne.total, 0),
  );
  protected readonly peutTelecharger = computed(
    () => this.choisis().size > 0 && !this.telechargement() && !this.chargement(),
  );
  /** Chosen, but holding nothing: the archive would carry an empty file rather than lie about it. */
  protected readonly choisisVides = computed(() =>
    this.lignes().filter((ligne) => this.isSelected(ligne.target) && ligne.total === 0),
  );

  constructor() {
    void this.charger();
  }

  protected isSelected(target: ExportCsvTarget): boolean {
    return this.choisis().has(target);
  }

  protected basculer(target: ExportCsvTarget, coche: boolean): void {
    const choisis = new Set(this.choisis());
    if (coche) {
      choisis.add(target);
    } else {
      choisis.delete(target);
    }
    this.choisis.set(choisis);
    this.message.set('');
  }

  private async charger(): Promise<void> {
    this.chargement.set(true);
    try {
      this.volumes.set(await this.api.volumes());
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.chargement.set(false);
    }
  }

  protected async telecharger(): Promise<void> {
    if (!this.peutTelecharger()) {
      return;
    }
    this.telechargement.set(true);
    this.erreur.set('');
    this.message.set('');
    try {
      this.message.set(await this.api.telecharger([...this.choisis()]));
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.telechargement.set(false);
    }
  }

  /* ------------------------------ Scénario YAML ----------------------------- */

  protected readonly exportScenarioEnCours = signal(false);
  protected readonly messageScenario = signal('');

  /**
   * Read-only, so nothing here is gated by the solver lock the imports follow:
   * it reads the edition and writes a file, it never touches the dataset.
   */
  protected async exporterScenario(): Promise<void> {
    this.exportScenarioEnCours.set(true);
    this.messageScenario.set(
      $localize`:@@dataSetup.exportingScenario:Export des données actuelles en fichier scénario...`,
    );
    try {
      this.messageScenario.set(await this.planningApi.exportScenario());
    } catch (error) {
      this.messageScenario.set(errorPrefix(error));
    } finally {
      this.exportScenarioEnCours.set(false);
    }
  }
}
