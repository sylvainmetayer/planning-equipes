import { DecimalPipe } from '@angular/common';
import {
  afterNextRender,
  computed,
  effect,
  inject,
  input,
  resource,
  ChangeDetectionStrategy,
  Component,
  Injector,
  ViewEncapsulation,
} from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RouterLink } from '@angular/router';
import { AnalysesApi } from '../../core/api/analyses-api';
import { RealiseApi } from '../../core/api/realise-api';
import {
  CelluleMarge,
  CompetenceStaffing,
  JourStaffing,
  StaffingSummary,
  TypologieStaffing,
} from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import { consumeQueryParam } from '../../core/view-query-params';
import { StatusMessage } from '../../shared/status-message';
import { FormationPage } from '../formation/formation-page';
import { libelleJour as jourCourt } from '../formation/formation';
import { libelleTranche, NiveauMarge, niveauMarge, signe } from '../marge/marge';
import { MarginBeforeGrid } from '../marge/margin-before-grid';
import { mesurePrecedente, resumePrecedent } from '../realise/mesure';

/** The tightest timeslot of one day before a solve, as the per-day table prints it. */
export interface MargeJour {
  label: string;
  tranche: string;
  niveau: NiveauMarge;
}

/** `-2` at `18:00-22:00`: the tightest cell of one day, from the « avant » margin. */
export function margeJour(cellule: CelluleMarge | null | undefined): MargeJour | null {
  return cellule
    ? {
        label: signe(cellule.marge),
        tranche: libelleTranche(cellule.debut, cellule.fin),
        niveau: niveauMarge(cellule.marge),
      }
    : null;
}

/**
 * Staffing-need calculator: how many animateurs the stands and créneaux
 * currently configured require at a minimum, before any animateur is entered.
 *
 * Everything is computed server-side (`GET /api/staffing`) on the very seats a
 * solve would have to fill. This page used to compute it in the browser from
 * `effectifMin` × open stands × créneaux, which ignored recurring horaires
 * (every stand counted open around the clock) and counted overlapping relay
 * vacations several times over — on a real 16-day edition it announced more
 * than 1500 animateurs for an event staffed by 153. See `StaffingAnalyzer` on the
 * backend for the methodology and its limits.
 *
 * The second section reads the same payload per game category — the bottleneck
 * view: which typologie the referential is short of specialists on. It
 * lives here rather than on a route of its own because it is the same question,
 * the same computation and the same seats, split one level finer.
 *
 * A polyvalent counts as a specialist only where they declared the competence,
 * and shows up apart as a reinforcement everywhere else — the same asymmetry
 * the "le ninja est un renfort, jamais un spécialiste" decision settles for the
 * fragilité screen, so two neighbouring screens do not answer the same question
 * two different ways.
 *
 * Every figure leads to the screen that changes it: a bound to the opening
 * hours or the legal settings it is proved on, a day to its stands' hours. The
 * « avant » margin — who has not declared the date unavailable, minus the seats
 * — is a column of the per-day table, its grid one fold below; « À former »,
 * once a tab of its own, is the foot of this one (`section=former` scrolls to
 * it once the figures above it are on screen, then leaves the address).
 */
@Component({
  selector: 'app-staffing-page',
  imports: [
    StatusMessage,
    RouterLink,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    MatTableModule,
    MatTooltipModule,
    DecimalPipe,
    FormationPage,
    MarginBeforeGrid,
  ],
  templateUrl: './staffing-page.html',
  // The margin's scale colours its column here as it does its grid.
  styleUrls: ['./staffing-page.css', '../marge/marge.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StaffingPage {
  /**
   * False when the Diagnostic page hosts this screen as one of its tabs: the
   * page then carries the title, and a second heading would only repeat it.
   */
  readonly entete = input(true);

  protected readonly columns = [
    'jour',
    'standsOuverts',
    'sieges',
    'heures',
    'picSimultane',
    'picAvecPause',
    'minimumJour',
    'marge',
    'horaires',
  ];
  protected readonly competenceColumns = [
    'typologie',
    'sieges',
    'minimumTotal',
    'specialistes',
    'manque',
  ];
  private readonly analysesApi = inject(AnalysesApi);
  private readonly realiseApi = inject(RealiseApi);

  /**
   * The previous edition's realised measure, shown beside each game category
   * for information only: nothing on this tab reads it, and a failed read
   * shows nothing rather than an error.
   */
  private readonly precedenteResource = resource({
    loader: () => this.realiseApi.previousEdition(),
  });
  protected readonly precedente = computed(() => {
    const mesure =
      this.precedenteResource.status() === 'error' ? null : this.precedenteResource.value();
    return mesure?.available ? mesure : null;
  });
  /** The game category table's columns, the previous edition's last when there is one. */
  protected readonly colonnesTypologie = computed(() =>
    this.precedente() ? [...this.competenceColumns, 'precedente'] : this.competenceColumns,
  );

  /** Read when the screen opens: nothing here changes without a new solve or a referential edit. */
  private readonly staffing = resource({ loader: () => this.analysesApi.staffing() });
  protected readonly summary = retainedValue(this.staffing);
  protected readonly loading = this.staffing.isLoading;
  protected readonly error = errorText(this.staffing);

  /** The margin before any solve: a column of the per-day table, and the grid behind it. */
  private readonly margin = resource({ loader: () => this.analysesApi.margin('AVANT') });
  protected readonly rapportMarge = retainedValue(this.margin);
  /** ISO date → the tightest timeslot of that day; a day the margin does not know gets a dash. */
  protected readonly marginByDay = computed(
    () =>
      new Map(
        (this.rapportMarge()?.jours ?? []).map((jour) => [jour.date, margeJour(jour.pireCellule)]),
      ),
  );

  /** `2026-09-01` → `01/09`, the way the links to a day's opening hours name it. */
  protected readonly jourCourt = jourCourt;

  private readonly injector = inject(Injector);

  constructor() {
    // `?onglet=former` lands here with `section=former`: « À former » is at the
    // foot. A landing, not a view: obeyed once, then dropped from the address,
    // so coming back to the tab does not scroll again.
    consumeQueryParam('section', (section) =>
      section === 'former' ? this.scrollToTrainingOnceLoaded() : undefined,
    );
  }

  /**
   * Scrolls to « À former » once both tables above it have their figures: the
   * section starts where they end, and scrolling while they still load lands
   * on what they push down a moment later.
   */
  private scrollToTrainingOnceLoaded(): Promise<void> {
    return new Promise((resolve) => {
      const loaded = effect(
        () => {
          if (this.staffing.isLoading() || this.margin.isLoading()) {
            return;
          }
          loaded.destroy();
          afterNextRender(
            () => {
              document.getElementById('a-former')?.scrollIntoView({ block: 'start' });
              resolve();
            },
            { injector: this.injector },
          );
        },
        { injector: this.injector },
      );
    });
  }

  /**
   * What one animateur may work during the week the workload bound was proved
   * on. Read straight from the payload: it used to be a browser-side division
   * of an event-wide capacity by the number of weeks, which only ever
   * reconstructed the weekly ceiling and hid the fact that a week the event
   * barely touches offers far less than it.
   */
  protected readonly heuresParSemaine = computed(
    () => this.summary()?.capaciteHeuresParAnimateur ?? 0,
  );

  /** Hours the busiest week has to cover — the numerator of the workload bound. */
  protected readonly heuresSemaineCritique = computed(
    () => this.summary()?.semaineCritique?.heures ?? 0,
  );

  /**
   * The projection line, shown only when it says something the bounds do not:
   * an availability nobody declared cannot be projected, and a projection
   * equal to the floor would only repeat it.
   */
  protected readonly projectionLabel = computed(() => {
    const summary = this.summary();
    if (
      !summary?.indisponibilitesDeclarees ||
      summary.minimumAvecIndisponibilites <= summary.minimumTotal
    ) {
      return '';
    }
    const projete = summary.minimumAvecIndisponibilites;
    const minimum = summary.minimumTotal;
    return $localize`:@@staffing.projection.description:Avec les indisponibilités déclarées, il faudrait ${projete}:projete: personnes pour un besoin de ${minimum}:minimum: — une projection, pas une borne : les recrues à venir sont supposées aussi souvent indisponibles.`;
  });

  protected readonly competence = computed<CompetenceStaffing | null>(
    () => this.summary()?.parCompetence ?? null,
  );

  /**
   * Which of the two referentials the seats are built from is still empty,
   * named — the server used to answer an all-zero summary for a missing stand,
   * a missing créneau and a missing animateur alike, and the screen blamed the
   * créneaux every time. A missing animateur is not listed here: the bounds
   * above are proven without one, and the bottleneck card says it.
   */
  protected readonly referentielsManquantsLabel = computed(() => {
    const manquants = this.summary()?.referentielsManquants ?? [];
    const phrases: string[] = [];
    if (manquants.includes('STANDS')) {
      phrases.push(
        $localize`:@@staffing.noStands:Aucun stand saisi pour le moment : sans stand, il n'y a aucun siège à pourvoir.`,
      );
    }
    if (manquants.includes('CRENEAUX')) {
      phrases.push(
        $localize`:@@staffing.noCreneaux:Aucun créneau saisi pour le moment : sans créneau, il n'y a aucun siège à pourvoir.`,
      );
    }
    return phrases.join(' ');
  });

  /**
   * How the shared polyvalent reserve reads against the shortfalls — an
   * indication, never a recruitment figure: a polyvalent covers any typologie
   * but only one seat at a time.
   */
  protected readonly reserveLabel = computed(() => {
    const competence = this.competence();
    if (!competence) {
      return '';
    }
    const polyvalents = competence.polyvalents;
    const manque = competence.manqueTotal;
    const manqueRenforts = competence.manquePolyvalents;
    if (manqueRenforts > 0) {
      // The reserve cannot be offered against its own shortage: the pool that
      // just came up short IS the pool of reinforcements.
      return $localize`:@@staffing.competence.shortfallOnReserve:${manque}:manque: places de plus que de spécialistes sur les typologies ci-dessous, dont ${manqueRenforts}:renforts: sur la typologie polyvalente elle-même : ce vivier est celui des renforts, rien ne peut absorber celui-là.`;
    }
    if (manque === 0) {
      return polyvalents === 0
        ? $localize`:@@staffing.competence.noBottleneck:Aucun goulot : chaque typologie compte au moins autant de spécialistes que sa borne.`
        : $localize`:@@staffing.competence.noBottleneckWithReserve:Aucun goulot : chaque typologie compte au moins autant de spécialistes que sa borne, et ${polyvalents}:reserve: polyvalents restent mobilisables en renfort sur n'importe laquelle.`;
    }
    if (!competence.typologieNinjaDefinie) {
      // A reserve of zero because no typologie carries the ninja flag is not a
      // shortage of backup: there is no such notion in this référentiel.
      return $localize`:@@staffing.competence.shortfallWithoutNinja:${manque}:manque: places de plus que de spécialistes sur les typologies ci-dessous. Aucune typologie n'est marquée « polyvalente » : il n'existe aucun renfort pour les absorber.`;
    }
    if (manque <= polyvalents) {
      return $localize`:@@staffing.competence.absorbable:${manque}:manque: places de plus que de spécialistes, cumulées sur les typologies ci-dessous. Les ${polyvalents}:reserve: polyvalents peuvent y répondre en renfort, mais chacun ne couvre qu'un siège à la fois.`;
    }
    return $localize`:@@staffing.competence.shortfall:${manque}:manque: places de plus que de spécialistes, cumulées sur les typologies ci-dessous, pour seulement ${polyvalents}:reserve: polyvalents en renfort : la réserve est plus mince que le cumul des manques.`;
  });

  protected readonly siegesNonAttribuesLabel = computed(() => {
    const sieges = this.competence()?.siegesNonAttribues ?? 0;
    return $localize`:@@staffing.competence.unattributed:${sieges}:count: sièges appartiennent à des stands proposant plusieurs typologies : l'un ou l'autre vivier peut les tenir, donc aucune typologie ne les revendique ici.`;
  });

  /**
   * The opposite of the line above, and a far louder one: a stand declaring no
   * typologie at all can only be held by a polyvalent — and by nobody when the
   * referential marks none.
   */
  protected readonly siegesReservesLabel = computed(() => {
    const competence = this.competence();
    const sieges = competence?.siegesReservesAuxPolyvalents ?? 0;
    return competence?.typologieNinjaDefinie
      ? $localize`:@@staffing.competence.polyvalentsOnly:${sieges}:count: sièges appartiennent à des stands ne proposant aucune typologie : seuls les polyvalents peuvent les tenir, ils sont donc comptés dans la ligne de la typologie polyvalente.`
      : $localize`:@@staffing.competence.nobodyEligible:${sieges}:count: sièges appartiennent à des stands sans typologie, et aucune typologie n'est marquée « polyvalente » : personne ne peut les tenir. Renseignez la typologie de ces stands.`;
  });

  /** The previous edition's absence rate and lost hours on this game category, « — » without one. */
  protected previousFor(ligne: TypologieStaffing): string {
    const mesure = mesurePrecedente(this.precedente(), ligne.typologie, ligne.label);
    return mesure ? resumePrecedent(mesure.counts) : '—';
  }

  /**
   * On the icon itself rather than in the template: `MatIcon` sets its own
   * `aria-hidden`, which drops the tooltip's `aria-describedby` — the label
   * has to be carried explicitly. Same trap as the shell's icon buttons.
   */
  protected readonly ninjaTooltip = $localize`:@@staffing.competence.ninjaTooltip:Typologie des polyvalents : ils peuvent tenir n'importe quel stand, mais ne comptent comme spécialistes que dans les compétences qu'ils déclarent.`;

  protected jourCritiqueLabel(jour: JourStaffing): string {
    const date = jour.date;
    const numero = jour.jour;
    const standsOuverts = jour.standsOuverts;
    return $localize`:@@staffing.busiestDay:Journée la plus chargée : J${numero}:jour: · ${date}:date: (${standsOuverts}:count: stands ouverts)`;
  }

  /** True for the bound that set the retained minimum, highlighted in the list. */
  protected estBorneRetenue(borne: StaffingSummary['borneRetenue']): boolean {
    return this.summary()?.borneRetenue === borne;
  }

  /** Days one animateur may work in an ISO week — six, art. L3132-1. */
  protected joursTravaillesMax(): number {
    return this.summary()?.joursTravaillesMaxParSemaine ?? 0;
  }

  /** The ISO week both weekly bounds were proved on, named for the reader. */
  protected semaineCritiqueLabel(): string {
    const semaine = this.summary()?.semaineCritique;
    if (!semaine) {
      return '';
    }
    const label = semaine.semaine;
    const jours = semaine.jours;
    const joursTravaillables = semaine.joursTravaillables;
    return $localize`:@@staffing.criticalWeek:Semaine la plus chargée : ${label}:semaine: · ${jours}:jours: jours d'événement, dont ${joursTravaillables}:travaillables: travaillables par personne`;
  }

  /** A typologie whose bound exceeds the animateurs declaring it: the bottleneck. */
  protected estGoulot(ligne: TypologieStaffing): boolean {
    return ligne.manque > 0;
  }
}
