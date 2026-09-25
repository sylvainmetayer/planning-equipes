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
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatSliderModule } from '@angular/material/slider';
import { dosageSummary } from '../../core/dosage';
import { intlLocale } from '../../core/locale';
import { KpiHistoriqueEntry } from '../../core/models';
import { HAUTEUR_COURBE, LARGEUR_COURBE } from '../solver/score-curve';
import {
  ReplayFigure,
  clampRank,
  coverageBand,
  editionsOfHistory,
  figureValue,
  kpiDelta,
  playbackStep,
  rankX,
  replaySeries,
  resolutionsOfEdition,
} from './rejeu';

/** Wall-clock pace of « Lecture »: one step a second — readable, not a slideshow. */
const PACE_MS = 1000;

/** Past this many solves, the marks merge into the line: one dot per solve would be a smear. */
const MAX_MARKS = 60;

/**
 * « Rejeu » of the Autopsie page: an edition's solves in order, as small
 * multiples on a shared rank axis, a cursor to walk them — slider, buttons,
 * arrow keys, a click on a curve, and a « Lecture » that never starts on its
 * own and never runs under `prefers-reduced-motion` — and a card saying what
 * the pointed solve measured and what moved since the one before. The page
 * owns the edition and the rank, so the table and the URL follow the cursor.
 */
@Component({
  selector: 'app-rejeu-panel',
  imports: [MatButtonModule, MatFormFieldModule, MatIconModule, MatSelectModule, MatSliderModule],
  templateUrl: './rejeu-panel.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RejeuPanel {
  /** Every row of the Autopsie, every edition: the panel picks its own. */
  readonly entries = input.required<readonly KpiHistoriqueEntry[]>();
  /** The edition replayed; `null` when « toutes les éditions » is chosen. */
  readonly editionId = input<string | null>(null);
  /** The rank asked for; `null` points at the latest solve. */
  readonly rank = input<number | null>(null);
  readonly editionIdChange = output<string | null>();
  readonly rankChange = output<number>();

  protected readonly width = LARGEUR_COURBE;
  protected readonly height = HAUTEUR_COURBE;

  protected readonly editions = computed(() => editionsOfHistory(this.entries()));
  protected readonly resolutions = computed(() => {
    const editionId = this.editionId();
    return editionId === null ? [] : resolutionsOfEdition(this.entries(), editionId);
  });
  protected readonly current = computed(() => clampRank(this.rank(), this.resolutions().length));
  protected readonly pointed = computed(() => this.resolutions()[this.current()] ?? null);
  protected readonly previous = computed(() => this.resolutions()[this.current() - 1] ?? null);
  protected readonly series = computed(() => replaySeries(this.resolutions()));
  protected readonly cursorX = computed(() => rankX(this.current(), this.resolutions().length));
  protected readonly showMarks = computed(() => this.resolutions().length <= MAX_MARKS);
  protected readonly delta = computed(() => {
    const pointed = this.pointed();
    return pointed ? kpiDelta(this.previous()?.kpi ?? null, pointed.kpi) : null;
  });
  protected readonly band = computed(() => {
    const pointed = this.pointed();
    return pointed ? coverageBand(pointed.kpi) : null;
  });

  /** `prefers-reduced-motion`: the replay then only moves when the operator moves it. */
  protected readonly reducedMotion = prefersReducedMotion();
  protected readonly playing = signal(false);
  private timer?: ReturnType<typeof setInterval>;

  constructor() {
    inject(DestroyRef).onDestroy(() => this.stop());
  }

  protected chooseEdition(editionId: string): void {
    this.stop();
    this.editionIdChange.emit(editionId === '' ? null : editionId);
  }

  protected moveTo(rank: number): void {
    const count = this.resolutions().length;
    if (count === 0) {
      return;
    }
    this.rankChange.emit(clampRank(rank, count));
  }

  protected step(direction: -1 | 1): void {
    this.stop();
    this.moveTo(this.current() + direction);
  }

  /** ←/→ on the curves, bound to their own element: the global listener sees nothing it does not own. */
  protected onKeydown(event: KeyboardEvent): void {
    if (event.key === 'ArrowLeft' || event.key === 'ArrowRight') {
      event.preventDefault();
      this.step(event.key === 'ArrowLeft' ? -1 : 1);
    }
  }

  /** A click on a curve moves the cursor to the nearest solve. */
  protected onCurveClick(event: MouseEvent): void {
    const target = event.currentTarget as Element | null;
    const box = target?.getBoundingClientRect();
    const count = this.resolutions().length;
    if (!box || box.width === 0 || count < 2) {
      return;
    }
    this.stop();
    this.moveTo(((event.clientX - box.left) / box.width) * (count - 1));
  }

  /**
   * Starts or stops « Lecture ». Never automatic, and refused under
   * `prefers-reduced-motion`: an animation that starts by itself is an
   * accessibility defect. It stops by itself at the last solve.
   */
  protected togglePlay(): void {
    if (this.playing()) {
      this.stop();
      return;
    }
    const count = this.resolutions().length;
    if (this.reducedMotion || count < 2) {
      return;
    }
    if (this.current() >= count - 1) {
      this.moveTo(0);
    }
    this.playing.set(true);
    this.timer = setInterval(() => this.advance(), PACE_MS);
  }

  private advance(): void {
    const count = this.resolutions().length;
    const next = this.current() + playbackStep(count);
    if (next >= count - 1) {
      this.moveTo(count - 1);
      this.stop();
      return;
    }
    this.moveTo(next);
  }

  private stop(): void {
    if (this.timer !== undefined) {
      clearInterval(this.timer);
      this.timer = undefined;
    }
    this.playing.set(false);
  }

  protected figureLabel(figure: ReplayFigure): string {
    switch (figure) {
      case 'hard':
        return $localize`:@@rejeu.figure.hard:Score dur`;
      case 'medium':
        return $localize`:@@rejeu.figure.medium:Medium hors plancher`;
      case 'soft':
        return $localize`:@@rejeu.figure.soft:Score souple`;
      case 'coverage':
        return $localize`:@@rejeu.figure.coverage:Couverture`;
      case 'fairness':
        return $localize`:@@rejeu.figure.fairness:Équilibre (σ des heures)`;
    }
  }

  /** The figure at the cursor, worded; a dash when that solve did not measure it. */
  protected figureAtCursor(figure: ReplayFigure): string {
    const pointed = this.pointed();
    const value = pointed ? figureValue(pointed.kpi, figure) : null;
    if (value === null) {
      return '—';
    }
    if (figure === 'coverage') {
      return `${value.toFixed(1)} %`;
    }
    if (figure === 'fairness') {
      return `${value.toFixed(1)} h`;
    }
    return String(value);
  }

  protected positionLabel(): string {
    const pointed = this.pointed();
    const date = pointed?.creeLe ? new Date(pointed.creeLe).toLocaleString(intlLocale()) : '';
    return $localize`:@@rejeu.position:Résolution ${this.current() + 1}:rank: sur ${this.resolutions().length}:count: — ${date}:date:`;
  }

  protected readonly sliderLabel = $localize`:@@rejeu.slider:Résolution pointée`;

  protected coverageLabel(entry: KpiHistoriqueEntry): string {
    const { postesPourvus, postesTotal } = entry.kpi;
    return `${postesPourvus} / ${postesTotal}`;
  }

  protected dureeLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.dureeSolveSecondes === null ? '—' : `${entry.kpi.dureeSolveSecondes} s`;
  }

  protected consignesLabel(entry: KpiHistoriqueEntry): string {
    return entry.kpi.journeesSousConsigne === null ? '—' : String(entry.kpi.journeesSousConsigne);
  }

  protected dosageLabel(entry: KpiHistoriqueEntry): string {
    return dosageSummary(entry.kpi.dosage);
  }

  /** « +4 », « −120 », « = » — a delta written with its sign, the minus a real one. */
  protected signed(value: number | null, digits = 0): string {
    if (value === null) {
      return '—';
    }
    if (value === 0) {
      return '=';
    }
    const text = Math.abs(value).toFixed(digits);
    return value > 0 ? `+${text}` : `−${text}`;
  }

  protected ruleKindLabel(kind: 'appeared' | 'disappeared' | 'changed'): string {
    switch (kind) {
      case 'appeared':
        return $localize`:@@rejeu.rule.appeared:apparue`;
      case 'disappeared':
        return $localize`:@@rejeu.rule.disappeared:disparue`;
      case 'changed':
        return '';
    }
  }

  protected dayLabel(day: { date: string; postes: number; pourvus: number }): string {
    return $localize`:@@rejeu.day:${day.date}:date: : ${day.pourvus}:pourvus: / ${day.postes}:postes: postes pourvus`;
  }
}

function prefersReducedMotion(): boolean {
  return (
    typeof window !== 'undefined' &&
    typeof window.matchMedia === 'function' &&
    window.matchMedia('(prefers-reduced-motion: reduce)').matches
  );
}
