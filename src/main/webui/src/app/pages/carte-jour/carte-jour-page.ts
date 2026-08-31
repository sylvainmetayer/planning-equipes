import { ChangeDetectionStrategy, Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { errorPrefix } from '../../core/error-message';
import { Emplacement, PlanningEvenement } from '../../core/models';
import { PlanningStateService } from '../../core/planning-state.service';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { CarteJourMap } from './carte-jour-map';
import { JourneeCarte, buildJourneesCarte, formatMinutes, instantCarte } from './carte-jour';

/** Step of the cursor, of the two arrow buttons and of the replay, in minutes. */
const PAS_MINUTES = 15;
/** Wall-clock pace of the replay: one step every 700 ms — readable, not a slideshow. */
const CADENCE_MS = 700;

/**
 * Time replay of one event day on the emplacement map (issue #306): a day, a
 * time cursor, and the stands of that instant coloured by what the persisted
 * plan says is happening there.
 *
 * Same read-only source and pure-builder pattern as the other views
 * (`planningState.loadForDisplay()` + `buildJourneesCarte()`): no dedicated
 * endpoint, and never a solve. What "open" means is defined once, in
 * `carte-jour.ts`, and nothing here re-decides it.
 */
@Component({
  selector: 'app-carte-jour-page',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatSliderModule,
    RouterLink,
    CarteJourMap
  ],
  templateUrl: './carte-jour-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CarteJourPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningEvenement | null>(null);
  /** Emplacement referential: the coordinates, and the places holding no stand today. */
  protected readonly emplacements = signal<Emplacement[]>([]);
  /** Day shown; null until the plan is loaded, then the first day of the event. */
  protected readonly jourSelectionne = signal<number | null>(null);
  /** Cursor position in minutes since midnight; null means "the day's opening hour". */
  protected readonly minutesSelectionnees = signal<number | null>(null);
  /** Emplacement the list highlights and the map rings; null when none is chosen. */
  protected readonly selection = signal<string | null>(null);
  protected readonly lecture = signal(false);

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private minuterie?: ReturnType<typeof setInterval>;

  protected readonly jours = computed<JourneeCarte[]>(() =>
    buildJourneesCarte(this.planning()?.postes ?? [])
  );

  /**
   * The day actually displayed. Resolved rather than corrected by an effect: a
   * `jour` from the URL naming a day the plan no longer holds falls back to the
   * first one instead of leaving the page blank.
   */
  protected readonly jourCourant = computed<JourneeCarte | null>(() => {
    const jours = this.jours();
    const selectionne = this.jourSelectionne();
    return jours.find((jour) => jour.jour === selectionne) ?? jours[0] ?? null;
  });

  /**
   * The instant the cursor points at, always inside the day. Clamped on read
   * rather than corrected on write: switching to a shorter day, or arriving
   * from a hand-edited link, must land on a real hour of that day.
   */
  protected readonly minutes = computed<number>(() => {
    const jour = this.jourCourant();
    if (!jour) {
      return 0;
    }
    const demande = this.minutesSelectionnees();
    if (demande === null) {
      return jour.debutMinutes;
    }
    return Math.min(Math.max(demande, jour.debutMinutes), jour.finMinutes);
  });

  protected readonly instant = computed(() =>
    instantCarte(this.jourCourant(), this.minutes(), this.emplacements())
  );

  /** Changes with the day and nothing else: the map re-frames then, never on a cursor step. */
  protected readonly cadrage = computed(() => String(this.jourCourant()?.jour ?? ''));

  protected readonly compteursLabel = computed(() => {
    const { standsOuverts, standsTotal, pourvus, sieges } = this.instant().compteurs;
    const heure = this.instant().heure;
    return $localize`:@@carteJour.compteurs:À ${heure}:heure: : ${standsOuverts}:ouverts: stand(s) ouvert(s) sur ${standsTotal}:total:, ${pourvus}:pourvus: place(s) pourvue(s) sur ${sieges}:sieges:`;
  });

  /** Said separately from the counters: it is the line the reader is looking for. */
  protected readonly alerteLabel = computed(() => {
    const { decouverts, partiels } = this.instant().compteurs;
    if (decouverts === 0 && partiels === 0) {
      return '';
    }
    return $localize`:@@carteJour.alerte:${decouverts}:decouverts: stand(s) ouvert(s) sans personne, ${partiels}:partiels: en sous-effectif`;
  });

  protected readonly nonSituesLabel = computed(() => {
    const nonSitues = this.instant().compteurs.nonSitues;
    return $localize`:@@carteJour.nonSitues.count:${nonSitues}:nonSitues: stand(s) sans emplacement géolocalisé — absents de la carte`;
  });

  protected readonly carteAriaLabel = computed(() =>
    $localize`:@@carteJour.map.ariaLabel:Carte des emplacements à ${this.instant().heure}:heure:. La liste sous la carte en donne l'équivalent lisible.`
  );

  /** True as soon as the cursor left the day's opening, or a place was picked. */
  protected readonly vueModifiee = computed(
    () => this.minutes() !== (this.jourCourant()?.debutMinutes ?? 0) || this.selection() !== null
  );

  protected readonly jourPrecedentLabel = $localize`:@@carteJour.previousDay:Jour précédent`;
  protected readonly jourSuivantLabel = $localize`:@@carteJour.nextDay:Jour suivant`;
  protected readonly reculerLabel = $localize`:@@carteJour.stepBack:Reculer d'un quart d'heure`;
  protected readonly avancerLabel = $localize`:@@carteJour.stepForward:Avancer d'un quart d'heure`;
  protected readonly lireLabel = $localize`:@@carteJour.play:Dérouler la journée`;
  protected readonly pauseLabel = $localize`:@@carteJour.pause:Arrêter le déroulé`;
  protected readonly curseurLabel = $localize`:@@carteJour.cursor.label:Heure de la journée`;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const jour = Number(params.get('jour'));
    this.jourSelectionne.set(Number.isFinite(jour) && jour > 0 ? jour : null);
    // Tolerant on purpose: an absent, empty, hand-edited or obsolete `t` falls
    // back to the day's opening rather than failing the page. The day's own
    // bounds finish the job, in `minutes()`.
    const brut = params.get('t');
    const minute = brut === null || brut.trim() === '' ? Number.NaN : Number(brut);
    this.minutesSelectionnees.set(Number.isFinite(minute) && minute >= 0 ? minute : null);
    void this.refresh();
    keepViewInQueryParams(() => {
      const courant = this.jourCourant();
      const premier = this.jours()[0];
      return {
        // The first day is the default, and a default is the absence of its param.
        jour: courant && premier && courant.jour !== premier.jour ? String(courant.jour) : null,
        t: courant && this.minutes() !== courant.debutMinutes ? String(this.minutes()) : null
      };
    });
    // The replay must not outlive the page: a page left with the cursor
    // running would keep ticking on a component nobody is looking at.
    inject(DestroyRef).onDestroy(() => this.arreter());
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [planning, emplacements] = await Promise.all([
        this.planningState.loadForDisplay(),
        // Coordinates and empty places only: a missing referential degrades the
        // map to what the plan itself carries rather than failing the page.
        this.api.get<Emplacement[]>('/api/emplacements').catch(() => [])
      ]);
      this.planning.set(planning);
      this.emplacements.set(emplacements);
    } catch (error) {
      this.planning.set(null);
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected selectionnerJour(jour: number): void {
    this.arreter();
    this.jourSelectionne.set(jour);
    // A new day has its own opening hour, and the cursor goes back to it.
    this.minutesSelectionnees.set(null);
    this.selection.set(null);
  }

  /** Steps to the previous/next day of the event; a no-op at either end. */
  protected decalerJour(delta: number): void {
    const jours = this.jours();
    const index = jours.findIndex((jour) => jour.jour === this.jourCourant()?.jour);
    const cible = jours[index + delta];
    if (cible) {
      this.selectionnerJour(cible.jour);
    }
  }

  protected estPremierJour(): boolean {
    return this.jours()[0]?.jour === this.jourCourant()?.jour;
  }

  protected estDernierJour(): boolean {
    const jours = this.jours();
    return jours[jours.length - 1]?.jour === this.jourCourant()?.jour;
  }

  protected deplacerCurseur(minutes: number): void {
    this.minutesSelectionnees.set(minutes);
  }

  protected decalerCurseur(pas: number): void {
    this.minutesSelectionnees.set(this.minutes() + pas * PAS_MINUTES);
  }

  protected choisirEmplacement(emplacementId: string): void {
    this.selection.update((courant) => (courant === emplacementId ? null : emplacementId));
  }

  /**
   * Starts or stops the replay. Never automatic: an animation that starts by
   * itself and cannot be stopped is an accessibility defect, so it takes a
   * click to run and the same button stops it. It also stops on its own at the
   * end of the day, and with the page.
   */
  protected basculerLecture(): void {
    if (this.lecture()) {
      this.arreter();
      return;
    }
    const jour = this.jourCourant();
    if (!jour) {
      return;
    }
    // Restarting from the end would show one frame and stop.
    if (this.minutes() >= jour.finMinutes) {
      this.minutesSelectionnees.set(jour.debutMinutes);
    }
    this.lecture.set(true);
    this.minuterie = setInterval(() => this.avancerLecture(), CADENCE_MS);
  }

  private avancerLecture(): void {
    const jour = this.jourCourant();
    const suivant = this.minutes() + PAS_MINUTES;
    if (!jour || suivant >= jour.finMinutes) {
      this.minutesSelectionnees.set(jour?.finMinutes ?? null);
      this.arreter();
      return;
    }
    this.minutesSelectionnees.set(suivant);
  }

  private arreter(): void {
    if (this.minuterie !== undefined) {
      clearInterval(this.minuterie);
      this.minuterie = undefined;
    }
    this.lecture.set(false);
  }

  protected reinitialiserVue(): void {
    this.arreter();
    this.minutesSelectionnees.set(null);
    this.selection.set(null);
  }

  /** Label of the slider's value bubble. Arrow-function field: the template must not rebuild it each pass. */
  protected readonly formatCurseur = (minutes: number): string => formatMinutes(minutes);
}
