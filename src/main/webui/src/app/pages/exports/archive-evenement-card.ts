import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { ActivatedRoute } from '@angular/router';
import { ArchiveEvenementApi } from '../../core/api/archive-evenement-api';
import { errorMessage } from '../../core/error-message';
import { ArchivePart } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';

/** One part of the archive as the card lists it. */
interface LigneArchive {
  part: ArchivePart;
  libelle: string;
  fichier: string;
  /** Why the part is greyed out; `null` when it can be taken. */
  indisponible: string | null;
}

/** The parts ticked to begin with: the individual documents are heavy, the review is for a meeting. */
const DEFAULT_PARTS: readonly ArchivePart[] = [
  'pdfGlobal',
  'equite',
  'heures',
  'referentiels',
  'scenario',
];

/** The fragment the former Export page carried for this card: Fichiers still reads it as its Archive tab. */
export const ARCHIVE_FRAGMENT = 'archive-evenement';

/**
 * « Archive de fin d'événement »: the exports an edition leaves behind, ticked
 * part by part, in one ZIP the server writes with a manifest. The parts that
 * read the plan are greyed out while there is none, the publication review
 * while nothing was published — what the archive would carry of them is empty.
 */
@Component({
  selector: 'app-archive-evenement-card',
  imports: [MatButtonModule, MatCardModule, MatCheckboxModule, MatIconModule, MatProgressBarModule],
  templateUrl: './archive-evenement-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ArchiveEvenementCard {
  private readonly api = inject(ArchiveEvenementApi);

  private readonly disponibilite = resource({ loader: () => this.api.availability() });
  protected readonly etat = retainedValue(this.disponibilite);
  protected readonly chargement = this.disponibilite.isLoading;
  protected readonly erreurChargement = errorText(this.disponibilite);

  private readonly choisis = signal<ReadonlySet<ArchivePart>>(new Set(DEFAULT_PARTS));
  protected readonly feuille = signal(false);
  protected readonly telechargement = signal(false);
  protected readonly message = signal('');
  protected readonly erreur = signal('');

  protected readonly lignes = computed<LigneArchive[]>(() => {
    const etat = this.etat();
    const withoutPlan = etat && !etat.planResolu ? this.noPlanReason() : null;
    return [
      {
        part: 'pdfGlobal' as const,
        libelle: $localize`:@@exports.archive.pdfGlobal:Planning global (PDF)`,
        fichier: 'planning-global.pdf',
        indisponible: withoutPlan,
      },
      {
        part: 'equite' as const,
        libelle: $localize`:@@exports.archive.equite:Équité (CSV)`,
        fichier: 'equite.csv',
        indisponible: withoutPlan,
      },
      {
        part: 'heures' as const,
        libelle: $localize`:@@exports.archive.heures:Heures travaillées (CSV)`,
        fichier: 'heures.csv',
        indisponible: withoutPlan,
      },
      {
        part: 'referentiels' as const,
        libelle: $localize`:@@exports.archive.referentiels:Référentiels (CSV)`,
        fichier: 'referentiels/*.csv',
        indisponible: null,
      },
      {
        part: 'scenario' as const,
        libelle: $localize`:@@exports.archive.scenario:Scénario de l'édition (YAML)`,
        fichier: 'scenario.yaml',
        indisponible: null,
      },
      {
        part: 'publication' as const,
        libelle: $localize`:@@exports.archive.publication:Relecture de publication (CSV)`,
        fichier: 'publication.csv',
        indisponible:
          etat && !etat.publie
            ? $localize`:@@exports.archive.jamaisPublie:aucune publication`
            : null,
      },
      {
        part: 'individuels' as const,
        libelle: $localize`:@@exports.archive.individuels:Plannings individuels (PDF et ICS)`,
        fichier: 'plannings-individuels/',
        indisponible: withoutPlan,
      },
    ];
  });

  /** What will really be asked for: ticked, and not greyed out. */
  protected readonly parties = computed<ArchivePart[]>(() =>
    this.lignes()
      .filter((ligne) => ligne.indisponible === null && this.choisis().has(ligne.part))
      .map((ligne) => ligne.part),
  );
  /** The layout choice is only worth offering while the individual documents are taken. */
  protected readonly individuelsPris = computed(() => this.parties().includes('individuels'));
  protected readonly peutTelecharger = computed(
    () => this.parties().length > 0 && !this.telechargement() && this.etat() !== undefined,
  );

  constructor() {
    const route = inject(ActivatedRoute, { optional: true });
    const hote = inject(ElementRef<HTMLElement>);
    // Reached through the fragment of an old address: bring the card into
    // view, wherever its host lays it out.
    afterNextRender(() => {
      if (route?.snapshot.fragment === ARCHIVE_FRAGMENT) {
        (hote.nativeElement as HTMLElement).scrollIntoView?.({ block: 'start' });
      }
    });
  }

  protected isSelected(part: ArchivePart): boolean {
    return this.choisis().has(part);
  }

  protected basculer(part: ArchivePart, coche: boolean): void {
    const choisis = new Set(this.choisis());
    if (coche) {
      choisis.add(part);
    } else {
      choisis.delete(part);
    }
    this.choisis.set(choisis);
    this.message.set('');
  }

  protected async telecharger(): Promise<void> {
    if (!this.peutTelecharger()) {
      return;
    }
    this.telechargement.set(true);
    this.erreur.set('');
    this.message.set('');
    try {
      this.message.set(
        await this.api.telecharger(this.parties(), this.feuille() ? 'feuille' : 'livret'),
      );
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.telechargement.set(false);
    }
  }

  private noPlanReason(): string {
    return $localize`:@@exports.archive.sansPlan:aucun plan résolu`;
  }
}
