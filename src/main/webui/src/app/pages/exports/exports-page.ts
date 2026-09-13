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
import { PlanningApi } from '../../core/api/planning-api';
import { errorMessage, errorPrefix } from '../../core/error-message';
import { CibleExportCsv, VolumesExportCsv } from '../../core/models';

/** One referential of the archive: what it is called, and how many rows it holds. */
interface LigneExport {
  cible: CibleExportCsv;
  libelle: string;
  fichier: string;
  total: number;
}

/**
 * « Exports » : ce que l'édition courante sait écrire d'elle-même, en regard
 * de l'écran d'imports qui le relit.
 *
 * <p>Deux formes, et deux usages. L'archive CSV réécrit les référentiels dans
 * la forme exacte que les onglets d'import relisent, chacun à cocher : une
 * équipe qui recopie ses stands d'une année sur l'autre n'emporte pas
 * forcément ses bénévoles avec. Le fichier scénario, lui, emporte l'édition
 * entière d'un coup — c'est ce que l'onglet « Scénario » des imports relit.</p>
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
  private readonly choisis = signal<ReadonlySet<CibleExportCsv>>(
    new Set<CibleExportCsv>(['TYPOLOGIES', 'EMPLACEMENTS', 'STANDS', 'ANIMATEURS']),
  );

  protected readonly lignes = computed<LigneExport[]>(() => {
    const volumes = this.volumes() ?? {};
    return [
      {
        cible: 'TYPOLOGIES' as const,
        libelle: $localize`:@@nav.link.typologies:Typologies`,
        fichier: 'typologies.csv',
        total: volumes.TYPOLOGIES ?? 0,
      },
      {
        cible: 'EMPLACEMENTS' as const,
        libelle: $localize`:@@nav.link.emplacements:Emplacements`,
        fichier: 'emplacements.csv',
        total: volumes.EMPLACEMENTS ?? 0,
      },
      {
        cible: 'STANDS' as const,
        libelle: $localize`:@@nav.link.stands:Stands`,
        fichier: 'stands.csv',
        total: volumes.STANDS ?? 0,
      },
      {
        cible: 'ANIMATEURS' as const,
        libelle: $localize`:@@nav.link.animateurs:Animateurs`,
        fichier: 'animateurs.csv',
        total: volumes.ANIMATEURS ?? 0,
      },
    ];
  });

  protected readonly total = computed(() =>
    this.lignes()
      .filter((ligne) => this.estChoisi(ligne.cible))
      .reduce((somme, ligne) => somme + ligne.total, 0),
  );
  protected readonly peutTelecharger = computed(
    () => this.choisis().size > 0 && !this.telechargement() && !this.chargement(),
  );
  /** Chosen, but holding nothing: the archive would carry an empty file rather than lie about it. */
  protected readonly choisisVides = computed(() =>
    this.lignes().filter((ligne) => this.estChoisi(ligne.cible) && ligne.total === 0),
  );

  constructor() {
    void this.charger();
  }

  protected estChoisi(cible: CibleExportCsv): boolean {
    return this.choisis().has(cible);
  }

  protected basculer(cible: CibleExportCsv, coche: boolean): void {
    const choisis = new Set(this.choisis());
    if (coche) {
      choisis.add(cible);
    } else {
      choisis.delete(cible);
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
