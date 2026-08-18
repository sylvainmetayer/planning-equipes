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
import { Animateur, PlanningFestival, PosteAffectation, TypologieItem } from '../../core/models';
import { standTypologies, typologieColorClass, typologieLabel, typologieLabels, typologiePrincipale } from '../../core/typologie-colors';
import { SelectionRecherche } from '../../shared/selection-recherche';

export interface AnimateurOption {
  id: string;
  label: string;
}

export interface TimelineBlock {
  posteId: string;
  standNom: string;
  /** Typologie ids proposed by the stand — drives the colour of the recap chips. */
  typologies: string[];
  heureDebut: string;
  heureFin: string;
  /** Position within the day's amplitude bar, as a 0-100 percentage. */
  offsetPercent: number;
  widthPercent: number;
  /**
   * The other animateurs holding a seat on the same stand, same créneau and
   * same window — who this person will actually be working with. Empty when
   * they hold the stand alone.
   */
  coequipiers: string[];
}

export interface TimelineGap {
  dureeMinutes: number;
  offsetPercent: number;
  widthPercent: number;
}

/** One chip of the "Stands à couvrir" recap, coloured after the stand's typologie. */
export interface TimelineStandChip {
  nom: string;
  /** Display labels of every typologie the stand proposes, sorted. */
  typologies: string[];
  colorClass: string;
  tooltip: string;
}

/** One entry of the typologie colour legend shown above the stand chips. */
export interface TimelineTypologieLegendItem {
  id: string;
  label: string;
  colorClass: string;
}

/** Distinct stands the animateur works on over the whole festival, for the header recap. */
export interface TimelineStandsSummary {
  count: number;
  /** Distinct game typologies across those stands — a stand may propose several. */
  typologieCount: number;
  stands: TimelineStandChip[];
  legend: TimelineTypologieLegendItem[];
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
    MatTooltipModule,
    SelectionRecherche
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
  /** Typologie referential, only used to turn ids into display labels. */
  protected readonly typologies = signal<TypologieItem[]>([]);

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

  protected readonly standsSummary = computed<TimelineStandsSummary>(() =>
    buildStandsSummary(this.days(), typologieLabels(this.typologies()))
  );

  /** Stand, hours and — the point of the addition — who else is on that line. */
  protected blockTooltip(block: TimelineBlock): string {
    const base = `${block.standNom} : ${block.heureDebut} – ${block.heureFin}`;
    if (block.coequipiers.length === 0) {
      return $localize`:@@timeline.tooltip.alone:${base}:poste: — seul(e) sur ce stand`;
    }
    const equipe = block.coequipiers.join(', ');
    return $localize`:@@timeline.tooltip.teammates:${base}:poste: — avec ${equipe}:equipe:`;
  }

  protected readonly standsSummaryLabel = computed(() => {
    const summary = this.standsSummary();
    return $localize`:@@timeline.stands.count:${summary.count}:count: stand(s) au total, ${summary.typologieCount}:typologieCount: typologie(s) de jeu`;
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
      const [planning, typologies] = await Promise.all([
        this.planningState.loadForDisplay(),
        // Labels only: a missing referential degrades the chips to raw ids
        // rather than failing the whole timeline.
        this.api.get<TypologieItem[]>('/api/typologies').catch(() => [])
      ]);
      this.planning.set(planning);
      this.typologies.set(typologies);
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

  /** Bridges the picker's id list with the page's single selected id. */
  protected readonly animateurSelection = computed(() => {
    const id = this.selectedAnimateurId();
    return id ? [id] : [];
  });

  protected onAnimateurSelection(ids: string[]): void {
    this.selectAnimateur(ids[0] ?? '');
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

/**
 * Distinct stands across every day, sorted alphabetically: an animateur usually
 * comes back to the same stand several times, so the raw block count would
 * overstate how many different places they have to learn. Same reasoning for
 * the typologie count: two stands of the same typologie are one game family to
 * learn, not two, so it is the second number that says how varied the job is.
 */
export function buildStandsSummary(days: TimelineDay[], labels: Map<string, string> = new Map()): TimelineStandsSummary {
  const typologiesByStand = new Map<string, Set<string>>();
  days.forEach((day) =>
    day.blocks.forEach((block) => {
      if (!block.standNom) {
        return;
      }
      let typologies = typologiesByStand.get(block.standNom);
      if (!typologies) {
        typologies = new Set();
        typologiesByStand.set(block.standNom, typologies);
      }
      block.typologies.forEach((typologie) => typologies!.add(typologie));
    })
  );

  const allTypologies = new Set<string>();
  const stands = Array.from(typologiesByStand.entries())
    .sort((left, right) => left[0].localeCompare(right[0]))
    .map(([nom, typologies]) => {
      typologies.forEach((typologie) => allTypologies.add(typologie));
      const ids = Array.from(typologies).sort((left, right) => left.localeCompare(right));
      const noms = ids.map((id) => typologieLabel(labels, id)).sort((left, right) => left.localeCompare(right));
      return {
        nom,
        typologies: noms,
        colorClass: typologieColorClass(typologiePrincipale(ids)),
        tooltip:
          noms.length === 0
            ? $localize`:@@timeline.stands.typologieNone:${nom}:stand: — aucune typologie renseignée`
            : $localize`:@@timeline.stands.typologieTooltip:${nom}:stand: — typologie(s) : ${noms.join(', ')}:typologies:`
      };
    });

  const legend = Array.from(allTypologies)
    .map((id) => ({ id, label: typologieLabel(labels, id), colorClass: typologieColorClass(id) }))
    .sort((left, right) => left.label.localeCompare(right.label));

  return { count: stands.length, typologieCount: allTypologies.size, stands, legend };
}

/** One entry per festival day the animateur works, sorted chronologically. */
export function buildAnimateurTimeline(postes: PosteAffectation[], animateurId: string): TimelineDay[] {
  // Built over every poste, not only this animateur's: who else holds a seat
  // on the same line is exactly what the teammate list needs.
  const equipesParLigne = equipesParLigneDeStand(postes);
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
    .map(([jour, day]) => buildTimelineDay(jour, day.date, day.postes, animateurId, equipesParLigne));
}

/** Identity of a staffed line: one stand, one créneau, one window — the same key the calendars group on. */
function ligneKey(poste: PosteAffectation): string {
  const creneau = poste.creneau!;
  const heureDebut = poste.heureDebutEffective ?? creneau.heureDebut;
  const heureFin = poste.heureFinEffective ?? creneau.heureFin;
  return `${poste.stand?.id}::${creneau.id}::${heureDebut}::${heureFin}`;
}

/** Every assigned animateur (id + display name) per staffed line. */
function equipesParLigneDeStand(postes: PosteAffectation[]): Map<string, { id: string; nom: string }[]> {
  const equipes = new Map<string, { id: string; nom: string }[]>();
  postes.forEach((poste) => {
    if (!poste.creneau || !poste.animateur) {
      return;
    }
    const key = ligneKey(poste);
    const equipe = equipes.get(key) ?? [];
    equipe.push({
      id: poste.animateur.id,
      nom: `${poste.animateur.prenom ?? ''} ${poste.animateur.nom ?? ''}`.trim() || poste.animateur.id
    });
    equipes.set(key, equipe);
  });
  return equipes;
}

function buildTimelineDay(
  jour: number,
  date: string | null,
  postes: PosteAffectation[],
  animateurId: string,
  equipesParLigne: Map<string, { id: string; nom: string }[]>
): TimelineDay {
  const spans = postes
    .map((poste) => {
      const creneau = poste.creneau!;
      const heureDebut = poste.heureDebutEffective ?? creneau.heureDebut;
      const heureFin = poste.heureFinEffective ?? creneau.heureFin;
      return {
        posteId: poste.id,
        standNom: poste.stand?.nom || poste.stand?.id || '',
        typologies: standTypologies(poste.stand),
        coequipiers: (equipesParLigne.get(ligneKey(poste)) ?? [])
          .filter((membre) => membre.id !== animateurId)
          .map((membre) => membre.nom)
          .sort((left, right) => left.localeCompare(right)),
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
    typologies: span.typologies,
    coequipiers: span.coequipiers,
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
