import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { CompetenceStaffing, JourStaffing, StaffingSummary, TypologieStaffing } from '../../core/models';
import { errorPrefix } from '../../core/error-message';

/**
 * Staffing-need calculator: how many animateurs the stands and créneaux
 * currently configured require at a minimum, before any animateur is entered.
 *
 * Everything is computed server-side (`GET /api/staffing`) on the very seats a
 * solve would have to fill. This page used to compute it in the browser from
 * `effectifMin` × open stands × créneaux, which ignored recurring horaires
 * (every stand counted open around the clock) and counted overlapping relay
 * vacations several times over — on edition-1708 it announced more than 1500
 * animateurs for an event staffed by 153. See `StaffingAnalyzer` on the
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
 */
@Component({
  selector: 'app-staffing-page',
  imports: [MatCardModule, MatIconModule, MatProgressBarModule, MatTableModule, MatTooltipModule, DecimalPipe],
  templateUrl: './staffing-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class StaffingPage {
  protected readonly columns = ['jour', 'standsOuverts', 'sieges', 'heures', 'picSimultane', 'picAvecPause'];
  protected readonly competenceColumns = ['typologie', 'sieges', 'minimumTotal', 'specialistes', 'manque'];
  protected readonly summary = signal<StaffingSummary | null>(null);
  // `false`, not `true`: the constructor calls `load()`, which flips it to
  // `true` synchronously before its first `await`. The initial value was
  // never observable, so `true` only claimed a loading state that no render
  // ever saw.
  protected readonly loading = signal(false);
  protected readonly error = signal('');

  protected readonly heuresParSemaine = computed(() => {
    const summary = this.summary();
    return summary && summary.nombreSemaines > 0 ? summary.capaciteHeuresParAnimateur / summary.nombreSemaines : 0;
  });

  protected readonly competence = computed<CompetenceStaffing | null>(() => this.summary()?.parCompetence ?? null);

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
      return $localize`:@@staffing.competence.shortfallOnReserve:${manque}:manque: places de plus que de spécialistes, cumulées sur les typologies ci-dessous, dont ${manqueRenforts}:renforts: sur la typologie polyvalente elle-même : ce vivier étant celui des renforts, rien ne peut absorber ce manque-là.`;
    }
    if (manque === 0) {
      return polyvalents === 0
        ? $localize`:@@staffing.competence.noBottleneck:Aucun goulot : chaque typologie compte au moins autant de spécialistes que sa borne.`
        : $localize`:@@staffing.competence.noBottleneckWithReserve:Aucun goulot : chaque typologie compte au moins autant de spécialistes que sa borne, et ${polyvalents}:reserve: polyvalents restent mobilisables en renfort sur n'importe laquelle.`;
    }
    if (!competence.typologieNinjaDefinie) {
      // A reserve of zero because no typologie carries the ninja flag is not a
      // shortage of backup: there is no such notion in this référentiel.
      return $localize`:@@staffing.competence.shortfallWithoutNinja:${manque}:manque: places de plus que de spécialistes, cumulées sur les typologies ci-dessous. Aucune typologie n'est marquée « polyvalente » dans le référentiel : il n'existe pas de renfort à mobiliser pour les absorber.`;
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
      : $localize`:@@staffing.competence.nobodyEligible:${sieges}:count: sièges appartiennent à des stands ne proposant aucune typologie, et aucune typologie n'est marquée « polyvalente » : personne dans le référentiel n'est habilité à les tenir. Renseignez la typologie de ces stands.`;
  });

  /**
   * On the icon itself rather than in the template: `MatIcon` sets its own
   * `aria-hidden`, which drops the tooltip's `aria-describedby` — the label
   * has to be carried explicitly. Same trap as the shell's icon buttons.
   */
  protected readonly ninjaTooltip = $localize`:@@staffing.competence.ninjaTooltip:Typologie des polyvalents : ses titulaires peuvent tenir n'importe quel stand, mais ne comptent comme spécialistes que dans les compétences qu'ils déclarent — ailleurs, ils sont un renfort.`;

  private readonly api = inject(ApiService);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    this.loading.set(true);
    try {
      this.summary.set(await this.api.get<StaffingSummary>('/api/staffing'));
      this.error.set('');
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

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

  protected pauseMinutes(): number {
    return this.summary()?.pauseMinimaleMinutes ?? 0;
  }

  /** A typologie whose bound exceeds the animateurs declaring it: the bottleneck. */
  protected estGoulot(ligne: TypologieStaffing): boolean {
    return ligne.manque > 0;
  }
}
