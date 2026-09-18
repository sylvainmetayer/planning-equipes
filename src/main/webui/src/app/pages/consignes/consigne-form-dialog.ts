import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  signal,
  untracked,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ConsignesApi } from '../../core/api/consignes-api';
import { normaliseHour } from '../../core/horaire-stand';
import {
  ApercuConsigneJour,
  ConsigneEdition,
  Emplacement,
  FenetreConsigne,
  PrereglageConsigne,
  Stand,
  TypologieItem,
  VacationRef,
} from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import {
  ConsigneForm,
  ErreurForm,
  FILTRES_VIDES,
  FenetreSaisie,
  FiltresStands,
  StandForm,
  applyToSelection,
  bandeLabel,
  buildDemande,
  cocherAffiches,
  erreursForm,
  fenetreLabel,
  fenetresSaisies,
  filterStands,
  formVide,
  heureSaisie,
  libelleDate,
  mergePreselection,
  parseFenetresSaisie,
  followDefaultWindows,
} from './consignes';

/** Poser a new consigne, modifier the one a row carries, or prolonger it on other dates. */
export type ModeConsigne = 'poser' | 'modifier' | 'prolonger';

export interface ConsigneFormData {
  mode: ModeConsigne;
  /** The row being modified or prolonged; `null` when posing. */
  consigne: ConsigneEdition | null;
  /** The dates ticked when the form opens (a deep link, or the row's own date). */
  datesInitiales: string[];
  /** The grid's dates strictly after today: the only ones offered. */
  datesCandidates: string[];
  prereglages: PrereglageConsigne[];
  stands: Stand[];
  typologies: TypologieItem[];
  emplacements: Emplacement[];
}

/**
 * The consigne form (issue #4): a preset or a band typed freely, the dates,
 * the motif, the day's default windows — then the stands, read from the
 * server for the first date and the band, ticked as it proposes them and
 * adjusted one by one or in bulk. « Aperçu » shows what the write would do,
 * date by date; « Enregistrer » sends the very same request.
 *
 * <p>The stands list is re-read whenever the date or the band moves, and
 * what was chosen for a stand survives the re-read: the server's proposal
 * only fills the rows it has not met yet.</p>
 */
@Component({
  selector: 'app-consigne-form-dialog',
  imports: [
    FormsModule,
    MatDialogModule,
    MatButtonModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './consigne-form-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsigneFormDialog {
  protected readonly editingLocked = inject(SolverJobService).editingLocked;
  protected readonly dialogRef =
    inject<MatDialogRef<ConsigneFormDialog, ApercuConsigneJour[] | null>>(MatDialogRef);
  protected readonly data = inject<ConsigneFormData>(MAT_DIALOG_DATA);
  private readonly api = inject(ConsignesApi);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly mode = this.data.mode;

  protected readonly dates = signal<string[]>([...this.data.datesInitiales]);
  protected readonly fermetureDebut = signal(
    heureSaisie(this.data.consigne?.fermetureDebut ?? formVide().fermetureDebut),
  );
  protected readonly fermetureFin = signal(
    this.data.consigne ? heureSaisie(this.data.consigne.fermetureFin) : formVide().fermetureFin,
  );
  protected readonly motif = signal(this.data.consigne?.motif ?? '');
  protected readonly prereglage = signal<string | null>(this.data.consigne?.prereglage ?? null);
  protected readonly fenetres = signal<FenetreSaisie[]>(
    fenetresSaisies(this.data.consigne?.fenetres ?? []),
  );
  protected readonly stands = signal<StandForm[]>([]);
  protected readonly chargementStands = signal(false);
  protected readonly creneauxOfDay = signal<number | null>(null);

  protected readonly filtres = signal<FiltresStands>({ ...FILTRES_VIDES });
  /** The bulk line: windows as one line, a headcount, applied to the displayed ticked rows. */
  protected readonly bulkWindows = signal('');
  protected readonly bulkEffectif = signal('');

  protected readonly apercu = signal<ApercuConsigneJour[] | null>(null);
  protected readonly chargementApercu = signal(false);
  protected readonly enregistrement = signal(false);

  /** The dates offered: the candidates, plus the row's own date when modifying it. */
  protected readonly datesOffertes = computed(() => {
    const dates = new Set(this.data.datesCandidates);
    if (this.data.consigne && this.mode === 'modifier') {
      dates.add(this.data.consigne.date);
    }
    return [...dates].sort().map((date) => ({ date, libelle: libelleDate(date) }));
  });

  protected readonly form = computed<ConsigneForm>(() => ({
    dates: this.dates(),
    fermetureDebut: this.fermetureDebut(),
    fermetureFin: this.fermetureFin(),
    motif: this.motif(),
    prereglage: this.prereglage(),
    fenetres: this.fenetres(),
    stands: this.stands(),
  }));
  protected readonly erreurs = computed(() => erreursForm(this.form()));
  protected readonly invalide = computed(() => this.erreurs().length > 0);
  protected readonly messageErreur = computed(() => {
    const first = this.erreurs()[0];
    return first ? this.libelleErreur(first) : '';
  });

  protected readonly lignesAffichees = computed(() =>
    filterStands(this.stands(), this.data.stands, this.filtres()),
  );
  private readonly idsAffiches = computed(
    () => new Set(this.lignesAffichees().map((ligne) => ligne.standId)),
  );
  protected readonly tickedCount = computed(
    () => this.stands().filter((stand) => stand.coche).length,
  );
  protected readonly bandeAffichee = computed(() => {
    const debut = normaliseHour(this.fermetureDebut());
    const fin = this.fermetureFin() === '' ? null : normaliseHour(this.fermetureFin());
    return debut ? bandeLabel(debut, fin) : '';
  });

  protected readonly fenetreLabel = fenetreLabel;

  protected readonly formTitle = computed(() => {
    switch (this.mode) {
      case 'modifier':
        return $localize`:@@consignes.form.title.modifier:Modifier la consigne`;
      case 'prolonger':
        return $localize`:@@consignes.form.title.prolonger:Prolonger la consigne`;
      default:
        return $localize`:@@consignes.form.title.poser:Poser une consigne`;
    }
  });

  constructor() {
    // The stands are read for the first date and the band; the openings the
    // row already holds seed the rows the server has not met yet.
    const ouverturesInitiales = this.data.consigne?.ouvertures ?? [];
    effect(() => {
      const date = this.dates()[0] ?? null;
      const debut = normaliseHour(this.fermetureDebut());
      const finSaisie = this.fermetureFin();
      const fin = finSaisie === '' ? null : normaliseHour(finSaisie);
      if (!date || !debut || (finSaisie !== '' && fin === null)) {
        untracked(() => this.stands.set([]));
        return;
      }
      untracked(() => void this.loadStands(date, debut, fin, ouverturesInitiales));
    });
  }

  private async loadStands(
    date: string,
    fermetureDebut: string,
    fermetureFin: string | null,
    ouverturesInitiales: ConsigneEdition['ouvertures'],
  ): Promise<void> {
    this.chargementStands.set(true);
    this.apercu.set(null);
    try {
      const preselection = await this.api.preselection({ date, fermetureDebut, fermetureFin });
      this.creneauxOfDay.set(preselection.creneauxDuJour);
      this.stands.set(
        mergePreselection(preselection.stands, this.fenetres(), this.stands(), ouverturesInitiales),
      );
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargementStands.set(false);
    }
  }

  /* ------------------------------ the band ------------------------------ */

  /** A preset fills the band, the motif and the day's windows; the stands follow the windows. */
  protected choisirPrereglage(id: string | null): void {
    const prereglage = this.data.prereglages.find((candidat) => candidat.id === id) ?? null;
    this.prereglage.set(prereglage?.nom ?? null);
    if (!prereglage) {
      return;
    }
    this.fermetureDebut.set(heureSaisie(prereglage.fermetureDebut));
    this.fermetureFin.set(heureSaisie(prereglage.fermetureFin));
    this.motif.set(prereglage.motif);
    this.remplacerFenetres(fenetresSaisies(prereglage.fenetres));
  }

  /** The id of the preset whose name the form carries, for the selector. */
  protected readonly prereglageId = computed(
    () => this.data.prereglages.find((candidat) => candidat.nom === this.prereglage())?.id ?? null,
  );

  protected onDates(dates: string[]): void {
    this.dates.set([...dates].sort());
    this.apercu.set(null);
  }

  protected onMotif(motif: string): void {
    this.motif.set(motif);
    this.apercu.set(null);
  }

  /* ------------------------ the day's default windows ------------------------ */

  private remplacerFenetres(nouvelles: FenetreSaisie[]): void {
    const anciennes = this.fenetres();
    this.fenetres.set(nouvelles);
    this.stands.update((rows) => followDefaultWindows(rows, anciennes, nouvelles));
    this.apercu.set(null);
  }

  protected addWindow(): void {
    this.remplacerFenetres([...this.fenetres(), { debut: '', fin: '' }]);
  }

  protected retirerFenetre(index: number): void {
    this.remplacerFenetres(this.fenetres().filter((_, i) => i !== index));
  }

  protected patchFenetre(index: number, patch: Partial<FenetreSaisie>): void {
    this.remplacerFenetres(
      this.fenetres().map((fenetre, i) => (i === index ? { ...fenetre, ...patch } : fenetre)),
    );
  }

  /* ------------------------------- the stands ------------------------------- */

  protected patchFiltres(patch: Partial<FiltresStands>): void {
    this.filtres.update((filtres) => ({ ...filtres, ...patch }));
  }

  protected toutCocher(coche: boolean): void {
    this.stands.update((rows) => cocherAffiches(rows, this.idsAffiches(), coche));
    this.apercu.set(null);
  }

  protected patchStand(standId: string, patch: Partial<StandForm>): void {
    this.stands.update((rows) =>
      rows.map((row) => (row.standId === standId ? { ...row, ...patch } : row)),
    );
    this.apercu.set(null);
  }

  /** `type="number"` hands a number or `null` over; the field's text is what is read. */
  protected onEffectif(standId: string, valeur: unknown): void {
    this.patchStand(standId, { effectif: effectifDepuis(String(valeur ?? '')) });
  }

  protected addStandWindow(stand: StandForm): void {
    this.patchStand(stand.standId, { fenetres: [...stand.fenetres, { debut: '', fin: '' }] });
  }

  protected retirerFenetreStand(stand: StandForm, index: number): void {
    this.patchStand(stand.standId, { fenetres: stand.fenetres.filter((_, i) => i !== index) });
  }

  protected patchFenetreStand(
    stand: StandForm,
    index: number,
    patch: Partial<FenetreSaisie>,
  ): void {
    this.patchStand(stand.standId, {
      fenetres: stand.fenetres.map((fenetre, i) =>
        i === index ? { ...fenetre, ...patch } : fenetre,
      ),
    });
  }

  /** The bulk line read back, `null` while it cannot be read — the button waits. */
  protected readonly bulkWindowsParsed = computed(() =>
    this.bulkWindows().trim() === '' ? [] : parseFenetresSaisie(this.bulkWindows()),
  );
  protected readonly canApplyBulk = computed(
    () =>
      this.bulkWindowsParsed() !== null &&
      (this.bulkWindows().trim() !== '' || this.bulkEffectif().trim() !== ''),
  );

  /** Windows and/or headcount on every displayed ticked row; a part left empty is not touched. */
  protected applyBulk(): void {
    const fenetres = this.bulkWindowsParsed();
    if (!this.canApplyBulk() || fenetres === null) {
      return;
    }
    const effectifSaisi = this.bulkEffectif().trim();
    this.stands.update((rows) =>
      applyToSelection(rows, this.idsAffiches(), {
        fenetres: this.bulkWindows().trim() === '' ? undefined : fenetres,
        effectif: effectifSaisi === '' ? undefined : effectifDepuis(effectifSaisi),
      }),
    );
    this.apercu.set(null);
  }

  /* ------------------------------ preview, save ------------------------------ */

  protected async preview(): Promise<void> {
    if (this.invalide() || this.chargementApercu()) {
      return;
    }
    this.chargementApercu.set(true);
    try {
      this.apercu.set(await this.api.apercu(buildDemande(this.form())));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargementApercu.set(false);
    }
  }

  protected async save(): Promise<void> {
    if (this.invalide() || this.enregistrement()) {
      return;
    }
    this.enregistrement.set(true);
    try {
      this.dialogRef.close(await this.api.poser(buildDemande(this.form())));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.enregistrement.set(false);
    }
  }

  protected libelleDate(date: string): string {
    return libelleDate(date);
  }

  protected vacations(refs: VacationRef[]): string {
    return refs.map((ref) => bandeLabel(ref.heureDebut, ref.heureFin)).join(', ');
  }

  protected windowsText(fenetres: FenetreConsigne[]): string {
    return fenetres.map(fenetreLabel).join(', ');
  }

  private libelleErreur(erreur: ErreurForm): string {
    switch (erreur) {
      case 'DATES':
        return $localize`:@@consignes.form.error.dates:Choisissez au moins une date à venir.`;
      case 'FERMETURE_DEBUT':
        return $localize`:@@consignes.form.error.fermetureDebut:Indiquez l'heure à laquelle la fermeture commence.`;
      case 'FERMETURE_FIN':
        return $localize`:@@consignes.form.error.fermetureFin:L'heure de fin de fermeture est illisible ; vide, elle vaut minuit.`;
      case 'MOTIF':
        return $localize`:@@consignes.form.error.motif:Le motif est obligatoire : il est imprimé partout où la journée est dite modifiée.`;
      case 'FENETRE':
        return $localize`:@@consignes.form.error.fenetre:Chaque fenêtre a besoin d'une heure de début ; une fin vide vaut minuit.`;
    }
  }
}

/** What the headcount field holds: a positive integer, or nothing (inherit). */
function effectifDepuis(valeur: string): number | null {
  const parsed = Number(valeur);
  return valeur.trim() !== '' && Number.isInteger(parsed) && parsed > 0 ? parsed : null;
}
