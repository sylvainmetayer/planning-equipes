import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  output,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatRadioModule } from '@angular/material/radio';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ConsignesStore } from '../../core/consignes.store';
import { RapportOuvertures } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SolverJobService } from '../../core/solver-job.service';
import { correspondAuFiltre } from '../../core/text-filter';
import { StandBulkEditData, StandBulkEditDialog } from '../stands/stand-bulk-edit-dialog';
import { describeException, describeRule } from '../stands/stand-detail';
import {
  ExceptionComparee,
  MAX_STANDS_COMPARES,
  MIN_STANDS_COMPARES,
  GapKind,
  addStands,
  comparer,
  reglesComparees,
} from './comparaison-ouvertures';

/**
 * « Comparer » on the Ouvertures page: two to eight stands on the same days
 * and columns, each cell against a reference stand, with the rules of each
 * stand side by side underneath. Read-only: « Copier les horaires de la
 * référence vers… » hands over to the stands' bulk edit, preset, which has
 * its own checks and its own « Enregistrer ».
 *
 * The page owns the report and the address (`stands`, `ref`, `ecarts`); this
 * view is handed them two-way and decides nothing about what differs — that
 * is `comparaison-ouvertures.ts`.
 */
@Component({
  selector: 'app-comparaison-ouvertures',
  imports: [
    FormsModule,
    MatAutocompleteModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatRadioModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './comparaison-vue.html',
  styleUrl: './comparaison-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the page's own sheet.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OpeningsComparisonView {
  private readonly store = inject(ReferenceDataStore);
  private readonly consignes = inject(ConsignesStore);
  private readonly dialog = inject(MatDialog);
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  readonly rapport = input<RapportOuvertures | null>(null);
  /** The compared stands, in the order they were picked. The `stands` query param. */
  readonly standIds = model<string[]>([]);
  /** The reference asked for; the first stand when null or not compared. The `ref` query param. */
  readonly referenceId = model<string | null>(null);
  /** « Seulement les jours qui diffèrent ». The `ecarts` query param. */
  readonly ecartsSeulement = model(false);
  /** The bulk edit saved something: the page re-reads the report. */
  readonly modifie = output<void>();

  protected readonly max = MAX_STANDS_COMPARES;
  protected readonly min = MIN_STANDS_COMPARES;

  protected readonly comparaison = computed(() =>
    comparer(this.rapport(), this.standIds(), this.referenceId()),
  );
  protected readonly joursAffiches = computed(() => {
    const jours = this.comparaison().jours;
    return this.ecartsSeulement() ? jours.filter((jour) => jour.ecart) : jours;
  });
  protected readonly comparable = computed(
    () => this.comparaison().stands.length >= MIN_STANDS_COMPARES,
  );
  protected readonly regles = computed(() =>
    reglesComparees(
      this.store.stands(),
      this.comparaison().stands.map((stand) => stand.standId),
      this.comparaison().referenceId,
    ),
  );

  /* ------------------------------ selection ------------------------------ */

  protected readonly recherche = signal('');
  /** Said once after an addition went past eight, until the next change. */
  protected readonly refusLabel = signal('');

  /** Stands of the report not yet compared, narrowed by what is typed. */
  protected readonly propositions = computed(() => {
    const pris = new Set(this.standIds());
    const text = this.recherche();
    return (this.rapport()?.stands ?? [])
      .filter((ligne) => !pris.has(ligne.standId))
      .filter((ligne) => correspondAuFiltre(text, [ligne.nom, ligne.standId]))
      .slice(0, 50);
  });

  /** The game categories at least one stand of the report offers. */
  protected readonly typologies = computed(() => {
    const inReport = new Set((this.rapport()?.stands ?? []).map((ligne) => ligne.standId));
    const offertes = new Set(
      this.store
        .stands()
        .filter((stand) => inReport.has(stand.id))
        .flatMap((stand) => stand.typologiesProposees ?? []),
    );
    return this.store.typologies().filter((typologie) => offertes.has(typologie.id));
  });

  protected readonly complet = computed(() => this.standIds().length >= MAX_STANDS_COMPARES);

  protected readonly inconnusLabel = computed(() => {
    const inconnus = this.comparaison().inconnus.length;
    return inconnus === 0
      ? ''
      : $localize`:@@ouvertures.comparer.inconnus:${inconnus}:count: stand(s) de l'adresse n'existent plus : ignorés.`;
  });

  protected addStand(standId: string): void {
    this.applyAddition([standId]);
    this.recherche.set('');
  }

  protected addGameCategory(typologieId: string): void {
    const inReport = new Set((this.rapport()?.stands ?? []).map((ligne) => ligne.standId));
    const ids = this.store
      .stands()
      .filter(
        (stand) =>
          inReport.has(stand.id) && (stand.typologiesProposees ?? []).includes(typologieId),
      )
      .sort((left, right) => (left.nom || left.id).localeCompare(right.nom || right.id))
      .map((stand) => stand.id);
    this.applyAddition(ids);
  }

  private applyAddition(ids: string[]): void {
    const { selection, refuses } = addStands(this.standIds(), ids);
    this.standIds.set(selection);
    this.refusLabel.set(
      refuses === 0
        ? ''
        : $localize`:@@ouvertures.comparer.refuses:${refuses}:count: stand(s) non ajouté(s) : ${MAX_STANDS_COMPARES}:max: au plus. Au-delà, filtrez la grille Consulter.`,
    );
  }

  /** Drops a stand; when it was the reference, the one after it takes the role. */
  protected retirer(standId: string): void {
    const ids = this.standIds();
    const index = ids.indexOf(standId);
    const restants = ids.filter((id) => id !== standId);
    if (this.comparaison().referenceId === standId) {
      this.referenceId.set(restants[Math.min(index, restants.length - 1)] ?? null);
    }
    this.standIds.set(restants);
    this.refusLabel.set('');
  }

  protected choisirReference(standId: string): void {
    // The first stand is the default reference: naming it would only lengthen the address.
    this.referenceId.set(standId === this.standIds()[0] ? null : standId);
  }

  /* ------------------------------- reading ------------------------------- */

  protected libelleJour(date: string): string {
    const [, mois, jour] = date.split('-');
    return `${jour}/${mois}`;
  }

  protected consigneOf(date: string): string {
    return this.consignes.consigneOf(date)
      ? $localize`:@@ouvertures.comparer.sousConsigne:sous consigne`
      : '';
  }

  protected gapLabel(type: GapKind): string {
    switch (type) {
      case 'OUVERTURE':
        return $localize`:@@ouvertures.comparer.type.ouverture:ouverture`;
      case 'HEURES':
        return $localize`:@@ouvertures.comparer.type.heures:heures`;
      case 'EFFECTIF':
        return $localize`:@@ouvertures.comparer.type.effectif:effectif`;
    }
  }

  protected libelleSynthese(daysWithGap: number, firstGap: string | null): string {
    return daysWithGap === 0 || firstGap === null
      ? $localize`:@@ouvertures.comparer.synthese.identique:identique à la référence`
      : $localize`:@@ouvertures.comparer.synthese.ecart:diffère sur ${daysWithGap}:jours: jour(s) — d'abord ${firstGap}:ecart:`;
  }

  protected captionJour(jour: number, date: string): string {
    const consigne = this.consigneOf(date);
    const libelle = this.libelleJour(date);
    return consigne
      ? $localize`:@@ouvertures.comparer.caption.consigne:J${jour}:jour: — ${libelle}:date: (${consigne}:consigne:)`
      : $localize`:@@ouvertures.comparer.caption:J${jour}:jour: — ${libelle}:date:`;
  }

  protected readonly ruleText = describeRule;

  /** The autocomplete leaves its field empty once a stand is picked: the stand is in the list below. */
  protected readonly noText = (): string => '';

  protected exception(entree: ExceptionComparee): string {
    const row = describeException(entree.exception, entree.ouverture);
    return `${row.label} : ${row.value}`;
  }

  /* ------------------------------ harmonise ------------------------------ */

  /** The reference and the others, as the referential holds them: what the bulk edit needs. */
  private readonly copie = computed(() => {
    const comparaison = this.comparaison();
    const byId = new Map(this.store.stands().map((stand) => [stand.id, stand]));
    const modele = comparaison.referenceId ? byId.get(comparaison.referenceId) : undefined;
    const cibles = comparaison.stands
      .filter((stand) => stand.standId !== comparaison.referenceId)
      .map((stand) => byId.get(stand.standId))
      .filter((stand) => stand !== undefined);
    return modele && cibles.length > 0 ? { modele, cibles } : null;
  });
  protected readonly copiable = computed(() => this.copie() !== null);

  /**
   * Opens the stands' bulk edit on the other compared stands, set to take the
   * reference's whole schedule. Nothing is written from here: the dialog shows
   * what the copy brings, warns about a window beyond a maximum, and waits for
   * its own « Enregistrer ».
   */
  protected copyReference(): void {
    const copie = this.copie();
    if (!copie) {
      return;
    }
    this.dialog
      .open<StandBulkEditDialog, StandBulkEditData, boolean>(StandBulkEditDialog, {
        data: { stands: copie.cibles, modele: copie.modele },
        width: '48rem',
        maxWidth: '95vw',
        autoFocus: 'first-tabbable',
      })
      .afterClosed()
      .subscribe((enregistre) => {
        if (enregistre) {
          this.modifie.emit();
        }
      });
  }
}
