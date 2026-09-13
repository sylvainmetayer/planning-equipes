import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { RouterLink } from '@angular/router';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { errorPrefix } from '../../core/error-message';
import { urlLegifrance } from '../../core/legifrance';
import {
  DUREE_HEBDOMADAIRE_MAX_HEURES,
  DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES,
  ParametresLegaux,
} from '../../core/models';

/**
 * The legal parameters of the edition — the two weekly ceilings, the daily
 * rest, the gap between vacations, the on-post break, the hour the evening
 * starts at for the Équité screen — and, as the card right
 * below, the meal break: one record, two cards of the Paramètres page, either
 * save button sending the whole record. They used to sit on the Contraintes
 * page, next to the rules that read them; that is where nobody looked for
 * them, so every parameter of the edition is now set in one place.
 */
@Component({
  selector: 'app-parametres-legaux',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
    MatSlideToggleModule,
    RouterLink,
  ],
  templateUrl: './parametres-legaux.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresLegauxCard {
  protected readonly parametresLoading = signal(false);
  protected readonly parametresError = signal('');
  protected readonly parametresSaved = signal(false);
  protected readonly dureeHebdomadaireMaxHeures = signal<number | null>(null);
  protected readonly pauseSurPoste = signal(false);
  /** Minimum gap between two same-day vacations, in minutes (no legal floor: 0 lets blocks chain). */
  protected readonly gapBetweenVacationsMinutes = signal<number | null>(null);
  /** Minimum daily rest, in hours (art. L3131-1: 11 h; a lower value needs a collective agreement). */
  protected readonly reposQuotidienHeures = signal<number | null>(null);
  protected readonly articleReposQuotidien = urlLegifrance('L3131-1');
  protected readonly articlePause = urlLegifrance('L3121-16');
  protected readonly dureeHebdomadaireMaxMineurHeures = signal<number | null>(null);
  /** The meal break the organisation sets itself (issue #438): its length, and the two windows as HH:MM. */
  protected readonly coupureRepasMinutes = signal<number | null>(null);
  protected readonly coupureRepasMidiDebut = signal('');
  protected readonly coupureRepasMidiFin = signal('');
  protected readonly coupureRepasSoirDebut = signal('');
  protected readonly coupureRepasSoirFin = signal('');
  /** When the evening starts for the Équité screen (HH:MM), the organisation's rule (issue #497). */
  protected readonly heureDebutSoiree = signal('');
  /**
   * How long one vacation may run before the grid check warns. It used to bound
   * what the découpage produced; the découpage is gone and the rule it encoded
   * is not, so it is edited here with the other rules of the event.
   */
  protected readonly dureeVacationMaxHeures = signal<number | null>(null);

  /** Ordre public ceilings, mirrored from the server-side validation. */
  protected readonly ceilingAdultHours = DUREE_HEBDOMADAIRE_MAX_HEURES;
  protected readonly ceilingMinorHours = DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES;

  // The two ceilings are ordre public: the card states which article says so,
  // and links it, rather than asking the reader to take our word for it.
  protected readonly articleCeilingAdult = urlLegifrance('L3121-20');
  protected readonly articleCeilingMinor = urlLegifrance('L3162-1');

  private readonly constraintsApi = inject(ConstraintsApi);

  constructor() {
    void this.loadParametresLegaux();
  }

  protected async loadParametresLegaux(): Promise<void> {
    this.parametresLoading.set(true);
    this.parametresError.set('');
    try {
      this.read(await this.constraintsApi.legalParameters());
    } catch (error) {
      this.parametresError.set(errorPrefix(error));
    } finally {
      this.parametresLoading.set(false);
    }
  }

  private read(parametres: ParametresLegaux): void {
    this.dureeHebdomadaireMaxHeures.set(parametres.dureeHebdomadaireMaxMinutes / 60);
    this.dureeHebdomadaireMaxMineurHeures.set(parametres.dureeHebdomadaireMaxMineurMinutes / 60);
    this.gapBetweenVacationsMinutes.set(parametres.pauseMinimaleEntreVacationsMinutes);
    this.reposQuotidienHeures.set(parametres.reposQuotidienMinimalMinutes / 60);
    this.pauseSurPoste.set(parametres.pauseSurPoste);
    this.coupureRepasMinutes.set(parametres.coupureRepasMinutes);
    this.coupureRepasMidiDebut.set(parametres.coupureRepasMidiDebut ?? '');
    this.coupureRepasMidiFin.set(parametres.coupureRepasMidiFin ?? '');
    this.coupureRepasSoirDebut.set(parametres.coupureRepasSoirDebut ?? '');
    this.coupureRepasSoirFin.set(parametres.coupureRepasSoirFin ?? '');
    this.heureDebutSoiree.set(parametres.heureDebutSoiree ?? '');
    this.dureeVacationMaxHeures.set(parametres.dureeVacationMaxMinutes / 60);
  }

  /**
   * Saves every legal parameter the card holds — the two weekly ceilings, the
   * gap between vacations, the daily rest, the on-post break, the meal break
   * and the vacation ceiling — so that a save never silently resets a field the card did not
   * show: the server replaces the whole record. The bounds mirror the
   * server-side check (`ReferenceDataService.updateParametresLegaux`): a value
   * above the ordre public maximum is refused here too, so the administrator
   * gets an explanation rather than an HTTP 500. A lower value stays free — it
   * is more protective than the law.
   */
  protected async saveParametresLegaux(): Promise<void> {
    const heures = this.dureeHebdomadaireMaxHeures();
    const heuresMineur = this.dureeHebdomadaireMaxMineurHeures();
    const pauseMinutes = this.gapBetweenVacationsMinutes();
    const reposHeures = this.reposQuotidienHeures();
    const coupureMinutes = this.coupureRepasMinutes();
    const vacationMaxHeures = this.dureeVacationMaxHeures();
    if (
      heures === null ||
      heures <= 0 ||
      heuresMineur === null ||
      heuresMineur <= 0 ||
      pauseMinutes === null ||
      pauseMinutes < 0 ||
      reposHeures === null ||
      reposHeures < 0 ||
      coupureMinutes === null ||
      coupureMinutes < 0 ||
      vacationMaxHeures === null ||
      vacationMaxHeures <= 0 ||
      this.heureDebutSoiree() === ''
    ) {
      return;
    }
    if (heures > this.ceilingAdultHours) {
      this.parametresError.set(
        $localize`:@@constraints.legal.error.plafondMajeur:La durée hebdomadaire maximale des majeurs ne peut pas dépasser ${this.ceilingAdultHours}:hours: h (Code du travail art. L3121-20, disposition d'ordre public).`,
      );
      return;
    }
    if (heuresMineur > this.ceilingMinorHours) {
      this.parametresError.set(
        $localize`:@@constraints.legal.error.plafondMineur:La durée hebdomadaire maximale des mineurs ne peut pas dépasser ${this.ceilingMinorHours}:hours: h (Code du travail art. L3162-1).`,
      );
      return;
    }
    this.parametresLoading.set(true);
    this.parametresError.set('');
    this.parametresSaved.set(false);
    try {
      this.read(
        await this.constraintsApi.saveLegalParameters({
          dureeHebdomadaireMaxMinutes: Math.round(heures * 60),
          dureeHebdomadaireMaxMineurMinutes: Math.round(heuresMineur * 60),
          pauseMinimaleEntreVacationsMinutes: Math.round(pauseMinutes),
          reposQuotidienMinimalMinutes: Math.round(reposHeures * 60),
          pauseSurPoste: this.pauseSurPoste(),
          coupureRepasMinutes: Math.round(coupureMinutes),
          coupureRepasMidiDebut: this.coupureRepasMidiDebut(),
          coupureRepasMidiFin: this.coupureRepasMidiFin(),
          coupureRepasSoirDebut: this.coupureRepasSoirDebut(),
          coupureRepasSoirFin: this.coupureRepasSoirFin(),
          heureDebutSoiree: this.heureDebutSoiree(),
          dureeVacationMaxMinutes: Math.round(vacationMaxHeures * 60),
        }),
      );
      this.parametresSaved.set(true);
    } catch (error) {
      this.parametresError.set(errorPrefix(error));
    } finally {
      this.parametresLoading.set(false);
    }
  }
}
