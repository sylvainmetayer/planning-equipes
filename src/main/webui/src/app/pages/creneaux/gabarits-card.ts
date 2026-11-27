import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  OnInit,
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
import { GenerateurCreneauxApi } from '../../core/api/generateur-creneaux-api';
import { jourSemaineDe, libelleJour, libelleJourSemaine } from '../../core/horaire-stand';
import { AffectationGabarit, EtatGenerateur, Gabarit, RapportApplication } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ConfirmService } from '../../shared/confirm-dialog';
import { injectGelReferentiel } from '../../core/gel-referentiel.store';
import { JoursFeriesService } from '../../core/jours-feries.service';
import { PastilleFerie } from '../../shared/pastille-ferie';
import {
  GabaritDialog,
  GabaritDialogData,
  GabaritDialogResult,
  isOneOffSeries,
} from './gabarit-dialog';
import { GabaritsApplicationData, GabaritsApplicationDialog } from './gabarits-application-dialog';
import {
  affecterDates,
  bornesCalendrier,
  datesDePlage,
  libelleVacation,
  nomGabarit,
} from './gabarits';

/** One row of the calendar table, ready to render. */
interface LigneCalendrier {
  date: string;
  libelle: string;
  jourSemaine: string;
  gabaritId: number;
  enEcart: boolean;
  /** Under a consigne (issue #4): the templates ignore the date, and it never reads « en écart ». */
  sousConsigne: boolean;
  /** The public holiday's name that day, `null` on an ordinary day. */
  ferie: string | null;
}

/**
 * « Gabarits » : the templates on the left, the calendar on the right,
 * and the two gestures that connect them to the grid — apply (previewed
 * first) and recognise (what the grid already implies). The card is where an
 * edition that types its vacations by hand starts: the dates it assigns are
 * the edition's dates, before a single créneau exists (ADR 0032).
 */
@Component({
  selector: 'app-gabarits',
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
    PastilleFerie,
  ],
  templateUrl: './gabarits-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GabaritsCard implements OnInit {
  /** Editing is disabled while a solve runs, like the rest of the page. */
  readonly verrouille = input(false);
  /** The calendar was applied: the page reloads the grid, its verdict and its mode. */
  readonly applique = output<RapportApplication>();
  /** « Appliquer sans mémoriser » in a template's dialog: the page lays these timeslots as a series. */
  readonly serie = output<string>();

  private readonly destroyRef = inject(DestroyRef);
  private readonly api = inject(GenerateurCreneauxApi);
  private readonly crud = inject(ReferenceCrudService);
  private readonly dialog = inject(MatDialog);
  private readonly confirm = inject(ConfirmService);
  private readonly notifications = inject(NotificationService);
  private readonly feries = inject(JoursFeriesService);
  /**
   * Applying the calendar writes the grid of timeslots, which a CRENEAUX
   * freeze refuses (ADR 0052); the templates and the calendar themselves
   * stay open — the page's notice says why the one button is greyed.
   */
  private readonly gel = injectGelReferentiel();

  protected readonly etat = signal<EtatGenerateur | null>(null);
  protected readonly chargement = signal(false);
  protected readonly ecriture = signal(false);

  /** The add-dates form: a range and a template. */
  protected readonly du = signal('');
  protected readonly au = signal('');
  protected readonly gabaritChoisie = signal<number | null>(null);

  protected readonly gabarits = computed(() => this.etat()?.gabarits ?? []);
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
    const feries = this.feries.byDate();
    return this.calendrier().map((affectation) => ({
      date: affectation.date,
      libelle: libelleJour(affectation.date),
      jourSemaine: libelleJourSemaine(jourSemaineDe(affectation.date)),
      gabaritId: affectation.gabaritId,
      enEcart: ecarts.has(affectation.date),
      sousConsigne: sousConsigne.has(affectation.date),
      ferie: feries.get(affectation.date) ?? null,
    }));
  });
  protected readonly datesAAjouter = computed(() => datesDePlage(this.du(), this.au()));
  protected readonly peutAjouter = computed(
    () =>
      this.datesAAjouter().length > 0 &&
      this.gabaritChoisie() !== null &&
      !this.ecriture() &&
      !this.verrouille(),
  );
  protected readonly peutAppliquer = computed(
    () =>
      this.calendrier().length > 0 &&
      !this.ecriture() &&
      !this.verrouille() &&
      !this.gel.isFrozen('CRENEAUX'),
  );

  protected readonly libelleVacation = libelleVacation;

  constructor() {
    effect(() => void this.feries.load(this.calendrier().map((affectation) => affectation.date)));
  }

  ngOnInit(): void {
    void this.recharger();
  }

  protected nomDe(id: number): string {
    return nomGabarit(this.gabarits(), id);
  }

  /** Read again after anything that changes the grid: the drift is computed against it. */
  async recharger(): Promise<void> {
    this.chargement.set(true);
    try {
      const etat = await this.api.etat();
      this.etat.set(etat);
      if (this.gabaritChoisie() === null && etat.gabarits.length > 0) {
        this.gabaritChoisie.set(etat.gabarits[0].id ?? null);
      }
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  /* ------------------------------ Templates ------------------------------ */

  protected open(gabarit: Gabarit | null): void {
    if (this.verrouille()) {
      return;
    }
    const ref = this.dialog.open<GabaritDialog, GabaritDialogData, GabaritDialogResult>(
      GabaritDialog,
      { data: { gabarit }, width: '40rem', maxWidth: '95vw', autoFocus: 'first-tabbable' },
    );
    ref
      .afterClosed()
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe((ecrit) => {
        if (isOneOffSeries(ecrit)) {
          this.serie.emit(ecrit.fenetres);
          return;
        }
        if (ecrit) {
          if (this.gabaritChoisie() === null) {
            this.gabaritChoisie.set(ecrit.id ?? null);
          }
          void this.recharger();
        }
      });
  }

  protected async remove(gabarit: Gabarit): Promise<void> {
    if (this.verrouille() || gabarit.id == null) {
      return;
    }
    const dates = this.calendrier().filter(
      (affectation) => affectation.gabaritId === gabarit.id,
    ).length;
    const confirme = await this.confirm.ask({
      title: $localize`:@@gabarits.supprimer.title:Supprimer le gabarit « ${gabarit.nom}:nom: »`,
      message: $localize`:@@gabarits.supprimer.message:${dates}:count: date(s) ne seront plus gouvernées par aucun gabarit. Les créneaux déjà écrits restent tels quels.`,
      confirmLabel: $localize`:@@common.delete:Supprimer`,
      danger: true,
    });
    if (!confirme) {
      return;
    }
    this.ecriture.set(true);
    try {
      await this.api.delete(gabarit.id);
      if (this.gabaritChoisie() === gabarit.id) {
        this.gabaritChoisie.set(null);
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
    const gabaritId = this.gabaritChoisie();
    if (!this.peutAjouter() || gabaritId === null) {
      return;
    }
    await this.writeCalendar(affecterDates(this.calendrier(), this.datesAAjouter(), gabaritId));
  }

  protected async changerGabarit(date: string, gabaritId: number): Promise<void> {
    if (this.verrouille() || this.ecriture()) {
      return;
    }
    await this.writeCalendar(affecterDates(this.calendrier(), [date], gabaritId));
  }

  protected async retirer(date: string): Promise<void> {
    if (this.verrouille() || this.ecriture()) {
      return;
    }
    this.ecriture.set(true);
    try {
      this.etat.set(await this.api.withdraw([date]));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.ecriture.set(false);
    }
  }

  private async writeCalendar(calendrier: AffectationGabarit[]): Promise<void> {
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
  async apply(): Promise<void> {
    // The page's menu item stays enabled on an empty calendar: say why nothing opens.
    if (this.calendrier().length === 0 && !this.chargement()) {
      this.notifications.notify({
        title: $localize`:@@gabarits.appliquer.vide:Aucune date n'est affectée à un gabarit : posez d'abord des gabarits sur des dates du calendrier.`,
        variant: 'warning',
        timeout: 6000,
      });
      return;
    }
    if (!this.peutAppliquer()) {
      return;
    }
    let apercu: RapportApplication;
    this.ecriture.set(true);
    try {
      const generation = await this.api.preview({ source: 'GABARITS' });
      if (!generation.application) {
        return;
      }
      apercu = generation.application;
    } catch (error) {
      this.crud.reportError(error);
      return;
    } finally {
      this.ecriture.set(false);
    }
    const ref = this.dialog.open<
      GabaritsApplicationDialog,
      GabaritsApplicationData,
      RapportApplication | null
    >(GabaritsApplicationDialog, {
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
            title: $localize`:@@gabarits.application.done:Calendrier appliqué : ${rapport.crees}:crees: créneau(x) créé(s), ${rapport.supprimes}:supprimes: supprimé(s), ${rapport.conserves}:conserves: conservé(s).`,
            variant: 'success',
            timeout: 8000,
          });
          void this.recharger();
          this.applique.emit(rapport);
        }
      });
  }

  /**
   * What the grid implies replaces the templates and the calendar — said
   * before it is done. Public: the page offers it in « Générer des créneaux ».
   */
  async reconnaitre(): Promise<void> {
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
      title: $localize`:@@gabarits.reconnaitre.title:Reconnaître les gabarits`,
      message: $localize`:@@gabarits.reconnaitre.message:${apercu.gabarits.length}:count: gabarit(s) sur ${apercu.calendrier.length}:dates: date(s), lues dans les créneaux actuels. Elles remplacent les gabarits et le calendrier existants ; les créneaux ne changent pas.`,
      confirmLabel: $localize`:@@gabarits.reconnaitre.submit:Remplacer`,
      danger: this.gabarits().length > 0,
    });
    if (!confirme) {
      return;
    }
    this.ecriture.set(true);
    try {
      await this.api.reconnaitre();
      this.gabaritChoisie.set(null);
      await this.recharger();
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.ecriture.set(false);
    }
  }
}
