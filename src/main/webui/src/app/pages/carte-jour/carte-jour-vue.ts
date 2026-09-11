import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  computed,
  effect,
  inject,
  input,
  linkedSignal,
  model,
  signal,
  untracked,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatSliderModule } from '@angular/material/slider';
import { RouterLink } from '@angular/router';
import { Emplacement, PlanningEvenement } from '../../core/models';
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
 * One rendering of the Journée page (`pages/journee`), which owns the day and
 * the data: this view is handed the plan and the emplacement referential,
 * draws them through the pure `buildJourneesCarte()`, and keeps only the
 * cursor as view state of its own. What "open" means is defined once, in
 * `carte-jour.ts`, and nothing here re-decides it.
 *
 * <p>Rendered by the page inside a `@defer` block, and that matters beyond
 * style: `leaflet` (through `CarteJourMap`) must not reach the initial bundle
 * nor the chunk of the three other renderings of the day.</p>
 */
@Component({
  selector: 'app-carte-jour-vue',
  imports: [
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatSliderModule,
    RouterLink,
    CarteJourMap,
  ],
  templateUrl: './carte-jour-vue.html',
  styleUrl: './carte-jour-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class CarteJourView {
  readonly planning = input<PlanningEvenement | null>(null);
  /** Emplacement referential: the coordinates, and the places holding no stand today. */
  readonly emplacements = input<Emplacement[]>([]);
  /** The day number the page selected; the first day of the plan when null. */
  readonly jour = input<number | null>(null);
  /** The page's stand filter: its emplacement is the one highlighted, until a click picks another. */
  readonly stand = input('');
  /** Cursor position in minutes since midnight; null means "the day's opening hour". The `t` query param. */
  readonly minutesSelectionnees = model<number | null>(null, { alias: 't' });
  protected readonly lecture = signal(false);

  private minuterie?: ReturnType<typeof setInterval>;

  protected readonly jours = computed<JourneeCarte[]>(() =>
    buildJourneesCarte(this.planning()?.postes ?? []),
  );

  /** Day shown: the one the page selected, else the first of the plan. */
  protected readonly jourCourant = computed<JourneeCarte | null>(() => {
    const jours = this.jours();
    return jours.find((candidat) => candidat.jour === this.jour()) ?? jours[0] ?? null;
  });

  /**
   * Emplacement the list highlights and the map rings; null when none is
   * chosen. Starts on the place of the stand the page filters on, and a click
   * then picks another — or the same one again, to drop it.
   */
  protected readonly selection = linkedSignal<string | null>(() => {
    const stand = this.stand();
    if (!stand) {
      return null;
    }
    return (
      this.jourCourant()?.stands.find((candidat) => candidat.standId === stand)?.emplacement?.id ??
      null
    );
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
    instantCarte(this.jourCourant(), this.minutes(), this.emplacements()),
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

  protected readonly carteAriaLabel = computed(
    () =>
      $localize`:@@carteJour.map.ariaLabel:Carte des emplacements à ${this.instant().heure}:heure:. La liste sous la carte en donne l'équivalent lisible.`,
  );

  protected readonly reculerLabel = $localize`:@@carteJour.stepBack:Reculer d'un quart d'heure`;
  protected readonly avancerLabel = $localize`:@@carteJour.stepForward:Avancer d'un quart d'heure`;
  protected readonly lireLabel = $localize`:@@carteJour.play:Dérouler la journée`;
  protected readonly pauseLabel = $localize`:@@carteJour.pause:Arrêter le déroulé`;
  protected readonly curseurLabel = $localize`:@@carteJour.cursor.label:Heure de la journée`;

  constructor() {
    // A new day has its own opening hour: the replay stops and the cursor goes
    // back to it. Skipped for the day the view opens on, whose cursor comes
    // from the URL.
    let premier = true;
    effect(() => {
      this.jour();
      if (premier) {
        premier = false;
        return;
      }
      untracked(() => {
        this.stopReplay();
        this.minutesSelectionnees.set(null);
      });
    });
    // The replay must not outlive the view: a view left with the cursor
    // running would keep ticking on a component nobody is looking at.
    inject(DestroyRef).onDestroy(() => this.stopReplay());
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
      this.stopReplay();
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
      this.stopReplay();
      return;
    }
    this.minutesSelectionnees.set(suivant);
  }

  private stopReplay(): void {
    if (this.minuterie !== undefined) {
      clearInterval(this.minuterie);
      this.minuterie = undefined;
    }
    this.lecture.set(false);
  }

  /** Back to the day's opening and to no picked place; the page's reset calls it. */
  reinitialiser(): void {
    this.stopReplay();
    this.minutesSelectionnees.set(null);
    this.selection.set(null);
  }

  /** True as soon as the cursor left the day's opening, or a place was picked. */
  readonly modifiee = computed(
    () => this.minutes() !== (this.jourCourant()?.debutMinutes ?? 0) || this.selection() !== null,
  );

  /** Label of the slider's value bubble. Arrow-function field: the template must not rebuild it each pass. */
  protected readonly formatCurseur = (minutes: number): string => formatMinutes(minutes);
}
