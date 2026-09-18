import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { jourSemaineDe } from '../../core/horaire-stand';
import {
  AffectationJourneeType,
  EtatJourneesTypes,
  JourneeType,
  RapportApplicationJourneesTypes,
} from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { libelleJour, libelleJourSemaine } from '../../core/horaire-stand';
import { JourneeTypeDialog, JourneeTypeDialogData } from './journee-type-dialog';
import {
  JourneesTypesApplicationData,
  JourneesTypesApplicationDialog,
} from './journees-types-application-dialog';
import {
  affecterDates,
  bornesCalendrier,
  datesDePlage,
  libelleVacation,
  nomJourneeType,
  retirerDate,
} from './journees-types';

/** One row of the calendar table, ready to render. */
interface LigneCalendrier {
  date: string;
  libelle: string;
  jourSemaine: string;
  journeeTypeId: number;
  enEcart: boolean;
  /** Under a consigne (issue #4): the templates ignore the date, and it never reads « en écart ». */
  sousConsigne: boolean;
}

/**
 * « Journées types » : the templates on the left, the calendar on the right,
 * and the two gestures that connect them to the grid — apply (previewed
 * first) and recognise (what the grid already implies). The card is where an
 * edition that types its vacations by hand starts: the dates it assigns are
 * the edition's dates, before a single créneau exists (ADR 0032).
 */
@Component({
  selector: 'app-journees-types',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './journees-types-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class JourneesTypesCard {
  /** Editing is disabled while a solve runs, like the rest of the page. */
  readonly verrouille = input(false);
  /** The calendar was applied: the page reloads the grid, its verdict and its mode. */
  readonly applique = output<RapportApplicationJourneesTypes>();

  private readonly destroyRef = inject(DestroyRef);
  private readonly api = inject(JourneesTypesApi);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);

  protected readonly etat = signal<EtatJourneesTypes | null>(null);
  protected readonly chargement = signal(false);
  protected readonly ecriture = signal(false);

  /** The add-dates form: a range and a template. */
  protected readonly du = signal('');
  protected readonly au = signal('');
  protected readonly journeeTypeChoisie = signal<number | null>(null);

  protected readonly journeesTypes = computed(() => this.etat()?.journeesTypes ?? []);
  protected readonly calendrier = computed(() => this.etat()?.calendrier ?? []);
  protected readonly datesSousConsigne = computed(
    () => new Set(this.etat()?.datesSousConsigne ?? []),
  );
  /** A date under consigne is never « en écart »: the consigne is what its créneaux follow. */
  protected readonly driftingDates = computed(() => {
    const sousConsigne = this.datesSousConsigne();
    return new Set((this.etat()?.datesEnEcart ?? []).filter((date) => !sousConsigne.has(date)));
  });
  protected readonly bornes = computed(() => bornesCalendrier(this.calendrier()));
  protected readonly lignes = computed<LigneCalendrier[]>(() => {
    const ecarts = this.driftingDates();
    const sousConsigne = this.datesSousConsigne();
    return this.calendrier().map((affectation) => ({
      date: affectation.date,
      libelle: libelleJour(affectation.date),
      jourSemaine: libelleJourSemaine(jourSemaineDe(affectation.date)),
      journeeTypeId: affectation.journeeTypeId,
      enEcart: ecarts.has(affectation.date),
      sousConsigne: sousConsigne.has(affectation.date),
    }));
  });
  protected readonly datesAAjouter = computed(() => datesDePlage(this.du(), this.au()));
  protected readonly peutAjouter = computed(
    () =>
      this.datesAAjouter().length > 0 &&
      this.journeeTypeChoisie() !== null &&
      !this.ecriture() &&
      !this.verrouille(),
  );
  protected readonly peutAppliquer = computed(
    () => this.calendrier().length > 0 && !this.ecriture() && !this.verrouille(),
  );

  protected readonly libelleVacation = libelleVacation;

  constructor() {
    void this.recharger();
  }

  protected nomDe(id: number): string {
    return nomJourneeType(this.journeesTypes(), id);
  }

  /** Read again after anything that changes the grid: the drift is computed against it. */
  async recharger(): Promise<void> {
    this.chargement.set(true);
    try {
      const etat = await this.api.etat();
      this.etat.set(etat);
      if (this.journeeTypeChoisie() === null && etat.journeesTypes.length > 0) {
        this.journeeTypeChoisie.set(etat.journeesTypes[0].id ?? null);
      }
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /* ------------------------------ Templates ------------------------------ */

  protected ouvrir(journeeType: JourneeType | null): void {
    if (this.verrouille()) {
      return;
    }
    const ref = this.dialog.open<JourneeTypeDialog, JourneeTypeDialogData, JourneeType | null>(
      JourneeTypeDialog,
      { data: { journeeType }, width: '40rem', maxWidth: '95vw', autoFocus: 'first-tabbable' },
    );
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ecrit) => {
        if (ecrit) {
          if (this.journeeTypeChoisie() === null) {
            this.journeeTypeChoisie.set(ecrit.id ?? null);
          }
          void this.recharger();
        }
      });
  }

  protected async supprimer(journeeType: JourneeType): Promise<void> {
    if (this.verrouille() || journeeType.id == null) {
      return;
    }
    const dates = this.calendrier().filter(
      (affectation) => affectation.journeeTypeId === journeeType.id,
    ).length;
    const confirme = await this.confirm.ask({
      title: $localize`:@@journeesTypes.supprimer.title:Supprimer la journée type « ${journeeType.nom}:nom: »`,
      message: $localize`:@@journeesTypes.supprimer.message:${dates}:count: date(s) ne seront plus gouvernées par aucune journée type. Les créneaux déjà écrits restent tels quels.`,
      confirmLabel: $localize`:@@common.delete:Supprimer`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    this.ecriture.set(true);
    try {
      await this.api.delete(journeeType.id);
      if (this.journeeTypeChoisie() === journeeType.id) {
        this.journeeTypeChoisie.set(null);
      }
      await this.recharger();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.ecriture.set(false);
    }
  }

  /* ------------------------------ Calendar ------------------------------ */

  protected async ajouterDates(): Promise<void> {
    const journeeTypeId = this.journeeTypeChoisie();
    if (!this.peutAjouter() || journeeTypeId === null) {
      return;
    }
    await this.ecrireCalendrier(
      affecterDates(this.calendrier(), this.datesAAjouter(), journeeTypeId),
    );
  }

  protected async changerJourneeType(date: string, journeeTypeId: number): Promise<void> {
    if (this.verrouille() || this.ecriture()) {
      return;
    }
    await this.ecrireCalendrier(affecterDates(this.calendrier(), [date], journeeTypeId));
  }

  protected async retirer(date: string): Promise<void> {
    if (this.verrouille() || this.ecriture()) {
      return;
    }
    await this.ecrireCalendrier(retirerDate(this.calendrier(), date));
  }

  private async ecrireCalendrier(calendrier: AffectationJourneeType[]): Promise<void> {
    this.ecriture.set(true);
    try {
      this.etat.set(await this.api.setCalendrier(calendrier));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.ecriture.set(false);
    }
  }

  /* ------------------------------ Apply, recognise ------------------------------ */

  /** Previewed first, always: the dialog shows what the write will do and does it. */
  protected async appliquer(): Promise<void> {
    if (!this.peutAppliquer()) {
      return;
    }
    let apercu: RapportApplicationJourneesTypes;
    this.ecriture.set(true);
    try {
      apercu = await this.api.previewApplication();
    } catch (error) {
      this.crud.reportError(error);
      return;
    } finally {
      this.ecriture.set(false);
    }
    const ref = this.dialog.open<
      JourneesTypesApplicationDialog,
      JourneesTypesApplicationData,
      RapportApplicationJourneesTypes | null
    >(JourneesTypesApplicationDialog, {
      data: { apercu },
      width: '44rem',
      maxWidth: '95vw',
      autoFocus: 'first-tabbable',
    });
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((rapport) => {
        if (rapport) {
          this.notifications.notify({
            title: $localize`:@@journeesTypes.application.done:Calendrier appliqué : ${rapport.crees}:crees: créneau(x) créé(s), ${rapport.supprimes}:supprimes: supprimé(s), ${rapport.conserves}:conserves: conservé(s).`,
            variant: 'success',
            timeout: 8000,
          });
          void this.recharger();
          this.applique.emit(rapport);
        }
      });
  }

  /** What the grid implies replaces the templates and the calendar — said before it is done. */
  protected async reconnaitre(): Promise<void> {
    if (this.verrouille() || this.ecriture()) {
      return;
    }
    let apercu;
    this.ecriture.set(true);
    try {
      apercu = await this.api.previewReconnaissance();
    } catch (error) {
      this.crud.reportError(error);
      return;
    } finally {
      this.ecriture.set(false);
    }
    const confirme = await this.confirm.ask({
      title: $localize`:@@journeesTypes.reconnaitre.title:Reconnaître les journées types`,
      message: $localize`:@@journeesTypes.reconnaitre.message:${apercu.journeesTypes.length}:count: journée(s) type(s) sur ${apercu.calendrier.length}:dates: date(s), lues dans les créneaux actuels. Elles remplacent les journées types et le calendrier existants ; les créneaux ne changent pas.`,
      confirmLabel: $localize`:@@journeesTypes.reconnaitre.submit:Remplacer`,
      danger: this.journeesTypes().length > 0,
    });
    if (!confirme) {
      return;
    }
    this.ecriture.set(true);
    try {
      await this.api.reconnaitre();
      this.journeeTypeChoisie.set(null);
      await this.recharger();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.ecriture.set(false);
    }
  }
}
