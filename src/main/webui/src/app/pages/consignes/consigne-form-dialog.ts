import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  linkedSignal,
  resource,
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
import { bandeLabel, fenetreLabel, libelleDate } from '../../core/consigne-wording';
import { normaliseHour } from '../../core/horaire-stand';
import {
  ApercuConsigneJour,
  ConsigneEdition,
  Emplacement,
  FenetreConsigne,
  PrereglageConsigne,
  PreselectionConsigne,
  Stand,
  TypologieItem,
  VacationRef,
} from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { errorText } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import {
  ConsigneForm,
  ErreurForm,
  FILTRES_VIDES,
  FenetreSaisie,
  FiltresStands,
  RepasSaisie,
  StandForm,
  applyToSelection,
  buildDemande,
  cocherAffiches,
  erreursForm,
  fenetresSaisies,
  filterStands,
  formVide,
  heureSaisie,
  mergePreselection,
  parseFenetresSaisie,
  followDefaultWindows,
  repasSaisie,
} from './consignes';
import { ConsigneRepasFields, repasErrorLabel } from './consigne-repas-fields';

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
 * <p>The stands list is re-read whenever the date or the band moves — a
 * `resource()` keyed on the three, so a reply to a request the form has moved
 * past is dropped rather than landing over the current one — and what was
 * chosen for a stand survives the re-read: the server's proposal only fills
 * the rows it has not met yet.</p>
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
    ConsigneRepasFields,
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

  /** The dates offered: the candidates, plus the row's own date when modifying it. */
  protected readonly datesOffertes = computed(() => {
    const dates = new Set(this.data.datesCandidates);
    if (this.data.consigne && this.mode === 'modifier') {
      dates.add(this.data.consigne.date);
    }
    return [...dates].sort().map((date) => ({ date, libelle: libelleDate(date) }));
  });

  // A deep link may name a day already begun: it is not ticked, and the
  // dates rule below says so if it comes back.
  protected readonly dates = signal<string[]>(
    this.data.datesInitiales.filter((date) =>
      this.datesOffertes().some((option) => option.date === date),
    ),
  );
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
  /** The meal windows restated for the dates, the edition's when nothing is typed. */
  protected readonly repas = signal<RepasSaisie>(repasSaisie(this.data.consigne?.repas ?? null));

  /**
   * What the server proposes for the first date and the band. Keyed on the
   * three: a change of any of them starts a new request and the previous
   * one's reply, should it arrive later, is discarded by the resource.
   */
  private readonly preselection = resource({
    params: () => {
      const date = this.dates()[0] ?? null;
      const debut = normaliseHour(this.fermetureDebut());
      const finSaisie = this.fermetureFin();
      const fin = finSaisie === '' ? null : normaliseHour(finSaisie);
      if (!date || !debut || (finSaisie !== '' && fin === null)) {
        return undefined;
      }
      return { date, fermetureDebut: debut, fermetureFin: fin };
    },
    loader: ({ params }) => this.api.preselection(params),
  });
  protected readonly chargementStands = this.preselection.isLoading;
  protected readonly erreurStands = errorText(this.preselection);
  protected readonly creneauxOfDay = computed(() =>
    this.preselection.hasValue() ? this.preselection.value().creneauxDuJour : null,
  );
  private readonly effectifMaxByStand = new Map(
    this.data.stands.map((stand) => [stand.id, stand.effectifMax]),
  );
  /**
   * The rows as the form shows them: what the server proposed, merged over
   * what was already chosen. Written by every gesture on a stand, recomputed
   * on every new proposal over its own previous value — the openings the row
   * already holds seed the rows the server has not met yet. While a re-read
   * is in flight the rows stay as they are; without a date to read for, the
   * list is empty.
   */
  protected readonly stands = linkedSignal<
    { preselection: PreselectionConsigne | null; idle: boolean },
    StandForm[]
  >({
    source: () => ({
      preselection: this.preselection.hasValue() ? this.preselection.value() : null,
      idle: this.preselection.status() === 'idle',
    }),
    computation: ({ preselection, idle }, previous) => {
      if (idle) {
        return [];
      }
      if (!preselection) {
        return previous?.value ?? [];
      }
      return mergePreselection(
        preselection.stands,
        untracked(this.fenetres),
        previous?.value ?? [],
        this.data.consigne?.ouvertures ?? [],
        this.effectifMaxByStand,
      );
    },
  });

  protected readonly filtres = signal<FiltresStands>({ ...FILTRES_VIDES });
  /** The bulk line: windows as one line, a headcount, applied to the displayed ticked rows. */
  protected readonly bulkWindows = signal('');
  protected readonly bulkEffectif = signal('');

  protected readonly apercu = signal<ApercuConsigneJour[] | null>(null);
  protected readonly chargementApercu = signal(false);
  protected readonly enregistrement = signal(false);

  protected readonly form = computed<ConsigneForm>(() => ({
    dates: this.dates(),
    fermetureDebut: this.fermetureDebut(),
    fermetureFin: this.fermetureFin(),
    motif: this.motif(),
    prereglage: this.prereglage(),
    fenetres: this.fenetres(),
    stands: this.stands(),
    repas: this.repas(),
  }));
  protected readonly erreurs = computed(() =>
    erreursForm(
      this.form(),
      this.datesOffertes().map((option) => option.date),
    ),
  );
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

  /* ------------------------------ the band ------------------------------ */

  /** A preset fills the band, the motif, the day's windows and the meal windows; the stands follow the windows. */
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
    this.repas.set(repasSaisie(prereglage.repas));
  }

  protected onRepas(repas: RepasSaisie): void {
    this.repas.set(repas);
    this.apercu.set(null);
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
    this.remplacerFenetres([...this.fenetres(), { debut: '', fin: '', effectif: '' }]);
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

  /** `type="number"` hands a number or `null` over; the field's text is what is kept and judged. */
  protected onEffectif(stand: StandForm, index: number, valeur: unknown): void {
    this.patchFenetreStand(stand, index, { effectif: String(valeur ?? '') });
  }

  protected addStandWindow(stand: StandForm): void {
    this.patchStand(stand.standId, {
      fenetres: [...stand.fenetres, { debut: '', fin: '', effectif: '' }],
    });
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
        effectif: effectifSaisi === '' ? undefined : effectifSaisi,
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
      case 'FENETRE_ORDRE':
        return $localize`:@@consignes.form.error.fenetreOrdre:Une fenêtre finit après son début, dans la même journée.`;
      case 'FENETRE_BANDE':
        return $localize`:@@consignes.form.error.fenetreBande:Une fenêtre est entièrement dans la bande fermée : elle n'ouvrirait rien.`;
      case 'EFFECTIF':
        return $localize`:@@consignes.form.error.effectif:Un effectif est un nombre entier supérieur à zéro ; vide, il hérite.`;
      case 'EFFECTIF_MAX':
        return $localize`:@@consignes.form.error.effectifMax:Un effectif dépasse le maximum de son stand.`;
      default:
        return repasErrorLabel(erreur);
    }
  }
}
