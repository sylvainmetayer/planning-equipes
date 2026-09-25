import {
  ChangeDetectionStrategy,
  Component,
  LOCALE_ID,
  computed,
  inject,
  input,
} from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { LigneEquite, RapportEquite } from '../../core/models';
import { columnConstraint, libelleSolveur } from './equite';
import {
  RADAR_SIZE,
  RadarAxis,
  band,
  formatValue,
  medians,
  notableRanks,
  pointOn,
  polygon,
  radarAxes,
  ring,
  valuesOf,
} from './radar';

/** Where an axis label sits around the radar, in percentages of the frame. */
interface AxisLabel {
  axis: RadarAxis;
  left: number;
  top: number;
  side: 'left' | 'right' | 'center';
  value: string;
  median: string;
  second: string | null;
}

/** How far out the labels sit, as a fraction of the radius. */
const LABEL_RATIO = 1.12;

/**
 * One animateur's fairness as a shape: a radar over the columns of the
 * Équité report, the person against the edition's median and its min–max
 * band, and optionally a second person. A summary of the fiche's table, which
 * stays the readable and complete source next to it.
 *
 * <p>Hand-written SVG, no charting library (see AGENTS.md); the geometry is
 * the pure `radar.ts`, unit-tested without rendering. The axis labels are
 * HTML laid over the drawing rather than SVG text, so they wrap, follow the
 * theme's typography, and carry the same solver icon as the table.</p>
 */
@Component({
  selector: 'app-equite-radar',
  imports: [MatIconModule, MatTooltipModule],
  template: `
    <figure class="equite-radar">
      <div class="equite-radar-frame">
        <svg
          class="equite-radar-svg"
          [attr.viewBox]="'0 0 ' + size + ' ' + size"
          role="img"
          [attr.aria-label]="accessibleName()"
        >
          @for (grid of rings(); track $index) {
            <polygon class="equite-radar-grid" [attr.points]="grid" />
          }
          @for (spoke of spokes(); track spoke.column) {
            <line
              class="equite-radar-spoke"
              [attr.x1]="size / 2"
              [attr.y1]="size / 2"
              [attr.x2]="spoke.x"
              [attr.y2]="spoke.y"
            />
          }
          <polygon class="equite-radar-band" [attr.points]="bandPoints()" />
          <polygon class="equite-radar-median" [attr.points]="medianPoints()" />
          @if (secondPoints(); as points) {
            <polygon class="equite-radar-second" [attr.points]="points" />
          }
          <polygon class="equite-radar-person" [attr.points]="personPoints()" />
        </svg>
        @for (label of labels(); track label.axis.column) {
          <div
            class="equite-radar-label"
            [class.equite-radar-label-left]="label.side === 'left'"
            [class.equite-radar-label-right]="label.side === 'right'"
            [class.equite-radar-label-muted]="label.axis.meaningless || !label.axis.spread"
            [style.left.%]="label.left"
            [style.top.%]="label.top"
          >
            <span class="equite-radar-axis">
              {{ label.axis.label }}
              @if (label.axis.higherIsBetter) {
                <span class="equite-radar-better" i18n="@@equite.radar.better">↑ mieux</span>
              }
              @if (label.axis.measuredBySolver) {
                <mat-icon
                  inline
                  class="equite-solveur-icon"
                  aria-hidden="false"
                  [matTooltip]="solverLabel(label.axis.column)"
                  [attr.aria-label]="solverLabel(label.axis.column)"
                  >tune</mat-icon
                >
              }
            </span>
            <span class="equite-radar-value">{{ label.value }}</span>
            <span class="equite-radar-detail" i18n="@@equite.radar.median"
              >méd. {{ label.median }}</span
            >
            @if (label.second !== null) {
              <span class="equite-radar-detail">{{ label.second }}</span>
            }
            @if (label.axis.meaningless) {
              <span class="equite-radar-detail" i18n="@@equite.radar.meaningless"
                >non significatif</span
              >
            } @else if (!label.axis.spread) {
              <span class="equite-radar-detail" i18n="@@equite.radar.noSpread"
                >aucune dispersion</span
              >
            }
          </div>
        }
      </div>
      <figcaption class="equite-radar-legend">
        <span class="equite-radar-key">
          <span class="equite-radar-swatch equite-radar-swatch-person"></span>{{ row().nom }}
        </span>
        @if (second(); as other) {
          <span class="equite-radar-key">
            <span class="equite-radar-swatch equite-radar-swatch-second"></span>{{ other.nom }}
          </span>
        }
        <span class="equite-radar-key">
          <span class="equite-radar-swatch equite-radar-swatch-median"></span>
          <ng-container i18n="@@equite.radar.legend.median">Médiane de l'édition</ng-container>
        </span>
        <span class="equite-radar-key">
          <span class="equite-radar-swatch equite-radar-swatch-band"></span>
          <ng-container i18n="@@equite.radar.legend.band">Étendue min–max</ng-container>
        </span>
      </figcaption>
      <p class="empty-hint" i18n="@@equite.radar.note">
        Centre : le minimum de l'édition, bord : son maximum. Plus loin = plus chargé, sauf sur les
        axes « ↑ mieux ».
      </p>
      @if (alone()) {
        <p class="empty-hint" i18n="@@equite.radar.alone">
          Une seule personne affectée : rien à comparer.
        </p>
      }
    </figure>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EquiteRadar {
  readonly report = input.required<RapportEquite>();
  readonly row = input.required<LigneEquite>();
  /** A second person drawn over the first, to arbitrate between two. */
  readonly second = input<LigneEquite | null>(null);
  /** The optional axes to add to the five defaults. */
  readonly optionalAxes = input<readonly string[]>([]);

  private readonly locale = inject(LOCALE_ID);
  protected readonly size = RADAR_SIZE;

  protected readonly axes = computed(() => radarAxes(this.report(), this.optionalAxes()));
  protected readonly rings = computed(() =>
    [0.25, 0.5, 0.75, 1].map((ratio) => ring(this.axes(), ratio)),
  );
  protected readonly spokes = computed(() =>
    this.axes().map((axis) => ({ column: axis.column, ...pointOn(axis, 1) })),
  );
  protected readonly bandPoints = computed(() => band(this.axes()));
  protected readonly medianPoints = computed(() => polygon(medians(this.axes()), this.axes()));
  protected readonly personPoints = computed(() =>
    polygon(valuesOf(this.row(), this.axes()), this.axes()),
  );
  protected readonly secondPoints = computed(() => {
    const other = this.second();
    return other ? polygon(valuesOf(other, this.axes()), this.axes()) : null;
  });
  protected readonly alone = computed(() => this.report().lignes.length <= 1);

  protected readonly labels = computed<AxisLabel[]>(() => {
    const own = valuesOf(this.row(), this.axes());
    const other = this.second();
    const otherValues = other ? valuesOf(other, this.axes()) : null;
    return this.axes().map((axis, index) => {
      const { x, y } = pointOn(axis, LABEL_RATIO);
      return {
        axis,
        left: (x / RADAR_SIZE) * 100,
        top: (y / RADAR_SIZE) * 100,
        side: labelSide(Math.sin(axis.angle)),
        value: this.format(axis.column, own[index]),
        median: this.format(axis.column, axis.median),
        second:
          other && otherValues
            ? `${other.nom} : ${this.format(axis.column, otherValues[index])}`
            : null,
      };
    });
  });

  /**
   * What a screen reader hears instead of the shape: whose radar, and the axes
   * where the person stands out — among the three highest or lowest of the
   * edition. The fiche's table, right next to it, stays the complete reading.
   */
  protected readonly accessibleName = computed(() => {
    const name = this.row().nom;
    const ranks = notableRanks(this.report().lignes, this.row(), this.axes()).map((rank) => {
      if (rank.rank === 1) {
        return rank.from === 'top'
          ? $localize`:@@equite.radar.aria.highest:${rank.label}:axis: : la valeur la plus haute de l'édition`
          : $localize`:@@equite.radar.aria.lowest:${rank.label}:axis: : la valeur la plus basse de l'édition`;
      }
      return rank.from === 'top'
        ? $localize`:@@equite.radar.aria.top:${rank.label}:axis: : ${rank.rank}:rank:e valeur la plus haute sur ${rank.count}:count:`
        : $localize`:@@equite.radar.aria.bottom:${rank.label}:axis: : ${rank.rank}:rank:e valeur la plus basse sur ${rank.count}:count:`;
    });
    const summary =
      ranks.length > 0
        ? ranks.join(' ; ')
        : $localize`:@@equite.radar.aria.none:aucun écart marquant`;
    const other = this.second();
    return other
      ? $localize`:@@equite.radar.aria.withSecond:Radar de ${name}:name: face à la médiane de l'édition et à ${other.nom}:second: — ${summary}:summary:`
      : $localize`:@@equite.radar.aria:Radar de ${name}:name: face à la médiane de l'édition — ${summary}:summary:`;
  });

  protected solverLabel(column: string): string {
    return libelleSolveur(columnConstraint(this.report(), column));
  }

  private format(column: string, value: number): string {
    return formatValue(column, value, this.locale);
  }
}

/** Which way an axis label leans, from the sine of its angle: off the side it sits on. */
function labelSide(sine: number): AxisLabel['side'] {
  if (sine > 0.2) {
    return 'right';
  }
  return sine < -0.2 ? 'left' : 'center';
}
