import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { StandsApi } from '../../core/api/stands-api';
import { RapportOuvertures } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import {
  Cellules,
  ColonneGrille,
  cellulesDepuis,
  cellulesPartielles,
  colonnes,
  ecrireCellule,
  isPartialCell,
  readCell,
  recopierJour,
  saisie,
  standsModifies,
} from '../ouvertures/grille-horaires';
import {
  StandGridDay,
  cellValue,
  copyDayAbove,
  dayPreview,
  pasteBlock,
  standGrid,
} from './stand-grid';

/** What a closed cell shows, and one of the things typed to close one (`readCell`). */
const CLOSED = '-';

/**
 * « Horaires et effectifs » of the fiche stand: the stand's row of the Horaires
 * des stands grid, one line per day and one column per window, edited in place. A cell
 * is how many people the stand holds then; empty, « - » or 0 is closed. A
 * block pasted from a spreadsheet lands from the cell it is pasted in,
 * « Recopier ce jour » lays a day on every other one with the same windows,
 * Ctrl+D takes the day above. Nothing is written before « Enregistrer », and
 * the save is the Ouvertures grid's — its body, its precondition, its
 * compaction into rules server-side — for this one stand.
 */
@Component({
  selector: 'app-stand-grid-editor',
  imports: [MatButtonModule, MatIconModule, MatTooltipModule],
  templateUrl: './stand-grid-editor.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StandGridEditor {
  readonly standId = input.required<string>();
  readonly rapport = input.required<RapportOuvertures>();
  /** Date → the day template governing it, for the row labels. */
  readonly templates = input<ReadonlyMap<string, string>>(new Map());
  /** A solve running, or a STANDS freeze: the grid reads, it does not write. */
  readonly locked = input(false);
  /** The save went through: the page reads the report again. */
  readonly saved = output<void>();

  private readonly standsApi = inject(StandsApi);
  private readonly notifications = inject(NotificationService);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly colonnes = computed(() => colonnes(this.rapport()));
  protected readonly grid = computed(() =>
    standGrid(this.colonnes(), this.rapport().jours, this.templates()),
  );
  private readonly reference = computed(() => cellulesDepuis(this.rapport()));
  private readonly partial = computed(() => cellulesPartielles(this.rapport()));
  /** The cells as typed, reset whenever the report is read again. */
  protected readonly cells = linkedSignal<Cellules>(() => this.reference());
  /** Cells typed and not saved: the page asks before it is left. */
  readonly modified = computed(() =>
    standsModifies(this.cells(), this.reference()).includes(this.standId()),
  );
  protected readonly saving = signal(false);
  private readonly row = computed(
    () => this.rapport().stands.find((ligne) => ligne.standId === this.standId()) ?? null,
  );

  protected value(colonne: ColonneGrille | null): string {
    const effectif = cellValue(this.cells(), this.standId(), colonne);
    return effectif === null ? '' : String(effectif);
  }

  protected isPartial(colonne: ColonneGrille): boolean {
    return (
      isPartialCell(this.partial(), { standId: this.standId(), colonneId: colonne.colonneId }) &&
      cellValue(this.cells(), this.standId(), colonne) ===
        cellValue(this.reference(), this.standId(), colonne)
    );
  }

  protected isChanged(colonne: ColonneGrille): boolean {
    return (
      cellValue(this.cells(), this.standId(), colonne) !==
      cellValue(this.reference(), this.standId(), colonne)
    );
  }

  protected cellLabel(day: StandGridDay, index: number): string {
    const window = this.grid().windows[index];
    return $localize`:@@standFiche.grille.case:${day.date}:date: ${window.label}:creneau:`;
  }

  protected preview(day: StandGridDay): string {
    const stretches = dayPreview(this.cells(), this.standId(), this.grid(), day);
    if (stretches.length === 0) {
      return $localize`:@@standFiche.grille.ferme:fermé`;
    }
    return stretches
      .map((stretch) => `${stretch.heureDebut}–${stretch.heureFin} ×${stretch.effectif}`)
      .join(', ');
  }

  /** A typed value: a headcount, or closed; anything else is put back as it was. */
  protected onChange(colonne: ColonneGrille, event: Event): void {
    const field = event.target as HTMLInputElement;
    const read = readCell(field.value);
    if (read === undefined) {
      field.value = this.value(colonne);
      return;
    }
    this.cells.set(
      ecrireCellule(this.cells(), { standId: this.standId(), colonneId: colonne.colonneId }, read),
    );
  }

  /** A block from a spreadsheet, laid from this cell. */
  protected onPaste(dayIndex: number, windowIndex: number, event: ClipboardEvent): void {
    const text = event.clipboardData?.getData('text/plain') ?? '';
    if (!text.includes('\t') && !text.includes('\n')) {
      // One value: the field takes it as typed.
      return;
    }
    event.preventDefault();
    this.cells.set(
      pasteBlock(this.cells(), this.standId(), this.grid(), text, dayIndex, windowIndex),
    );
  }

  /** Ctrl+D takes the day above, as the other entry grids do; the key goes no further. */
  protected onKeydown(dayIndex: number, event: KeyboardEvent): void {
    if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'd') {
      event.preventDefault();
      event.stopPropagation();
      this.cells.set(copyDayAbove(this.cells(), this.standId(), this.grid(), dayIndex));
    }
  }

  /** « Recopier ce jour » : this day's windows laid on every other day that has the same hours. */
  protected copyDay(day: StandGridDay): void {
    this.cells.set(recopierJour(this.cells(), day.date, [this.standId()], this.colonnes()));
  }

  protected reset(): void {
    this.cells.set(this.reference());
  }

  /** The Ouvertures grid's save, for this stand alone, its stamp as the precondition. */
  protected async save(): Promise<void> {
    if (!this.modified() || this.saving() || this.locked()) {
      return;
    }
    const standId = this.standId();
    this.saving.set(true);
    try {
      const rapport = await this.standsApi.saveOpeningsGrid(
        saisie(this.cells(), [standId], this.colonnes(), {
          modifieLeParStand: new Map([[standId, this.row()?.modifieLe ?? null]]),
        }),
      );
      const ligne = rapport.stands[0];
      this.notifications.notify({
        title: $localize`:@@ouvertures.saisie.doneTitle:Horaires enregistrés`,
        message: ligne
          ? $localize`:@@standFiche.grille.enregistre:Réécrits en ${ligne.regles}:regles: règle(s) et ${ligne.exceptions}:exceptions: exception(s) datée(s) ; effectif de ${ligne.effectifMin}:min: à ${ligne.effectifMax}:max:.`
          : '',
        variant: 'success',
      });
      this.saved.emit();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.saving.set(false);
    }
  }

  protected readonly closed = CLOSED;
}
