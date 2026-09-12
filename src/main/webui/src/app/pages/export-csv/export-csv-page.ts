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
import { errorMessage } from '../../core/error-message';
import { CibleExportCsv, VolumesExportCsv } from '../../core/models';

/** One referential of the archive: what it is called, and how many rows it holds. */
interface LigneExport {
  cible: CibleExportCsv;
  libelle: string;
  fichier: string;
  total: number;
}

/**
 * « Export CSV » : l'édition courante réécrite dans la forme que ses propres
 * onglets d'import relisent.
 *
 * <p>Chaque référentiel est à cocher : l'archive tient ce qu'on a demandé et
 * rien d'autre, parce qu'une équipe qui veut recopier ses stands d'une année
 * sur l'autre n'emporte pas forcément ses bénévoles avec.</p>
 */
@Component({
  selector: 'app-export-csv-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './export-csv-page.html',
  styleUrl: '../../../styles/import-animateurs.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the import screens it mirrors.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ExportCsvPage {
  private readonly api = inject(ExportCsvApi);

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
}
