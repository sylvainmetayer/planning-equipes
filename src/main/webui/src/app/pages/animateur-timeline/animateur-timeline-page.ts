import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { uniqueById } from '../../core/date-utils';
import { NotificationService } from '../../core/notification.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { Animateur, PlanningFestival, PosteAffectation } from '../../core/models';

export interface AnimateurOption {
  id: string;
  label: string;
}

export interface TimelineBlock {
  posteId: string;
  standNom: string;
  heureDebut: string;
  heureFin: string;
  /** Position within the day's amplitude bar, as a 0-100 percentage. */
  offsetPercent: number;
  widthPercent: number;
}

export interface TimelineGap {
  dureeMinutes: number;
  offsetPercent: number;
  widthPercent: number;
}

export interface TimelineDay {
  jour: number;
  title: string;
  /** Raw first-block start / last-block end, shown as the day's worked span. */
  amplitudeDebut: string;
  amplitudeFin: string;
  amplitudeLabel: string;
  blocks: TimelineBlock[];
  gaps: TimelineGap[];
}

/**
 * Read-only per-animateur timeline for issue #69: daily amplitude, vacations
 * and the pauses/travel between them, at a glance — complements the global
 * heatmap (#68) with a view focused on one person, useful when manually
 * repairing a planning. Same read-only source and pure-builder pattern as
 * `calendar-day-page.ts` (`planningState.loadForDisplay()` + `buildDays()`);
 * no dedicated backend endpoint.
 */
@Component({
  selector: 'app-animateur-timeline-page',
  imports: [
    MatCardModule,
    MatButtonModule,
    MatFormFieldModule,
    MatSelectModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule
  ],
  templateUrl: './animateur-timeline-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AnimateurTimelinePage {
  protected readonly loading = signal(false);
  protected readonly exportBusy = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningFestival | null>(null);
  protected readonly selectedAnimateurId = signal<string | null>(null);

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);

  protected readonly animateurOptions = computed<AnimateurOption[]>(() => buildAnimateurOptions(this.planning()?.postes ?? []));

  protected readonly days = computed<TimelineDay[]>(() => {
    const animateurId = this.selectedAnimateurId();
    if (!animateurId) {
      return [];
    }
    return buildAnimateurTimeline(this.planning()?.postes ?? [], animateurId);
  });

  constructor() {
    this.selectedAnimateurId.set(this.route.snapshot.queryParamMap.get('animateur'));
    void this.refresh();
    // Keeps the selection in the URL so it survives a refresh (F5) and can be
    // bookmarked/shared, without piling up history entries.
    effect(() => {
      const animateurId = this.selectedAnimateurId();
      void this.router.navigate([], { relativeTo: this.route, queryParams: { animateur: animateurId }, replaceUrl: true });
    });
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.planning.set(await this.planningState.loadForDisplay());
      const options = this.animateurOptions();
      if (!this.selectedAnimateurId() || !options.some((option) => option.id === this.selectedAnimateurId())) {
        this.selectedAnimateurId.set(options[0]?.id ?? null);
      }
    } catch (error) {
      this.planning.set(null);
      const message = error instanceof Error ? error.message : String(error);
      this.error.set($localize`:@@common.errorPrefix:Erreur : ${message}:message:`);
    } finally {
      this.loading.set(false);
    }
  }

  protected selectAnimateur(animateurId: string): void {
    this.selectedAnimateurId.set(animateurId);
  }

  protected exportPdf(): Promise<void> {
    return this.export('pdf', 'application/pdf');
  }

  protected exportIcs(): Promise<void> {
    return this.export('ics', 'text/calendar');
  }

  /**
   * Same server-side exports as the global archive of the Solveur page
   * (`/api/planning/export/{pdf,ics}/animateur/{id}`), but for the animateur
   * currently displayed only — the planning is POSTed as the request body, so
   * what gets exported is exactly what the timeline shows.
   */
  private async export(format: 'pdf' | 'ics', contentType: string): Promise<void> {
    const animateurId = this.selectedAnimateurId();
    if (!animateurId || this.exportBusy()) {
      return;
    }
    this.exportBusy.set(true);
    try {
      const planning = await this.planningState.require();
      const filename = exportFilename(this.animateurOptions(), animateurId, format);
      const url = `/api/planning/export/${format}/animateur/${encodeURIComponent(animateurId)}`;
      this.notifications.notify({
        title: await this.api.downloadPost(url, filename, planning, contentType),
        variant: 'success'
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      this.notifications.notify({
        title: $localize`:@@timeline.exportFailed:Export impossible`,
        message,
        variant: 'error'
      });
    } finally {
      this.exportBusy.set(false);
    }
  }

  protected gapTooltip(gap: TimelineGap): string {
    return $localize`:@@timeline.gap.tooltip:Pause ou déplacement : ${formatDuration(gap.dureeMinutes)}:duree:`;
  }
}

export function buildAnimateurOptions(postes: PosteAffectation[]): AnimateurOption[] {
  const animateurs = uniqueById(
    postes.map((poste) => poste.animateur).filter((animateur): animateur is Animateur => !!animateur)
  );
  const labels = animateurs.map((animateur) => `${animateur.prenom ?? ''} ${animateur.nom ?? ''}`.trim() || animateur.id);
  const counts = new Map<string, number>();
  labels.forEach((label) => counts.set(label, (counts.get(label) ?? 0) + 1));
  return animateurs
    .map((animateur, index) => {
      const label = labels[index];
      return { id: animateur.id, label: (counts.get(label) ?? 0) > 1 ? `${label} (${animateur.id})` : label };
    })
    .sort((left, right) => left.label.localeCompare(right.label));
}

/**
 * `planning-Jeanne-Dupont.pdf` rather than the raw id, so a downloaded file
 * stays readable — every character a file system may choke on is folded to `-`.
 */
export function exportFilename(options: AnimateurOption[], animateurId: string, format: 'pdf' | 'ics'): string {
  const label = options.find((option) => option.id === animateurId)?.label ?? animateurId;
  const safeLabel = label.replace(/[^\p{L}\p{N}]+/gu, '-').replace(/^-+|-+$/g, '') || 'animateur';
  return `planning-${safeLabel}.${format}`;
}

/** One entry per festival day the animateur works, sorted chronologically. */
export function buildAnimateurTimeline(postes: PosteAffectation[], animateurId: string): TimelineDay[] {
  const byDay = new Map<number, { date: string | null; postes: PosteAffectation[] }>();
  postes.forEach((poste) => {
    const creneau = poste.creneau;
    if (!creneau || poste.animateur?.id !== animateurId) {
      return;
    }
    let day = byDay.get(creneau.jour);
    if (!day) {
      day = { date: creneau.date ?? null, postes: [] };
      byDay.set(creneau.jour, day);
    }
    day.postes.push(poste);
  });

  return Array.from(byDay.entries())
    .sort((left, right) => left[0] - right[0])
    .map(([jour, day]) => buildTimelineDay(jour, day.date, day.postes));
}

function buildTimelineDay(jour: number, date: string | null, postes: PosteAffectation[]): TimelineDay {
  const spans = postes
    .map((poste) => {
      const creneau = poste.creneau!;
      const heureDebut = poste.heureDebutEffective ?? creneau.heureDebut;
      const heureFin = poste.heureFinEffective ?? creneau.heureFin;
      return {
        posteId: poste.id,
        standNom: poste.stand?.nom || poste.stand?.id || '',
        heureDebut,
        heureFin,
        startMinutes: minutesOfDay(heureDebut),
        endMinutes: endMinutesOfDay(heureFin)
      };
    })
    .sort((left, right) => left.startMinutes - right.startMinutes);

  const amplitudeDebutMinutes = Math.min(...spans.map((span) => span.startMinutes));
  const amplitudeFinMinutes = Math.max(...spans.map((span) => span.endMinutes));
  const range = Math.max(1, amplitudeFinMinutes - amplitudeDebutMinutes);

  const blocks: TimelineBlock[] = spans.map((span) => ({
    posteId: span.posteId,
    standNom: span.standNom,
    heureDebut: span.heureDebut,
    heureFin: span.heureFin,
    offsetPercent: ((span.startMinutes - amplitudeDebutMinutes) / range) * 100,
    widthPercent: ((span.endMinutes - span.startMinutes) / range) * 100
  }));

  const gaps: TimelineGap[] = [];
  for (let index = 1; index < spans.length; index += 1) {
    const previous = spans[index - 1];
    const next = spans[index];
    const dureeMinutes = next.startMinutes - previous.endMinutes;
    if (dureeMinutes > 0) {
      gaps.push({
        dureeMinutes,
        offsetPercent: ((previous.endMinutes - amplitudeDebutMinutes) / range) * 100,
        widthPercent: (dureeMinutes / range) * 100
      });
    }
  }

  const amplitudeDebut = spans.find((span) => span.startMinutes === amplitudeDebutMinutes)!.heureDebut;
  const amplitudeFin = spans.find((span) => span.endMinutes === amplitudeFinMinutes)!.heureFin;

  return {
    jour,
    title: date
      ? $localize`:@@calendarDay.dayTitleWithDate:Jour ${jour}:jour: — ${date}:date:`
      : $localize`:@@calendarDay.dayTitle:Jour ${jour}:jour:`,
    amplitudeDebut,
    amplitudeFin,
    amplitudeLabel: $localize`:@@timeline.amplitude:${amplitudeDebut}:debut: – ${amplitudeFin}:fin: (${formatDuration(amplitudeFinMinutes - amplitudeDebutMinutes)}:duree:)`,
    blocks,
    gaps
  };
}

/** `'00:00'` means midnight, i.e. the end of this festival day — never the start of the next one (this app's `jour` never spans two calendar dates). */
function endMinutesOfDay(heureFin: string): number {
  const minutes = minutesOfDay(heureFin);
  return minutes === 0 ? 1440 : minutes;
}

function minutesOfDay(time: string): number {
  const [hours, minutes] = time.split(':').map(Number);
  return hours * 60 + minutes;
}

function formatDuration(totalMinutes: number): string {
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours === 0) {
    return $localize`:@@timeline.duration.minutesOnly:${minutes}:minutes: min`;
  }
  if (minutes === 0) {
    return $localize`:@@timeline.duration.hoursOnly:${hours}:hours: h`;
  }
  return $localize`:@@timeline.duration.hoursAndMinutes:${hours}:hours: h ${minutes}:minutes:`;
}
