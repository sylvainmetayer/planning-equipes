import { NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { PlanningEvenement, RapportPauses } from '../../core/models';
import { formatDuration } from '../../core/time-of-day';
import { ValidationsStore } from '../../core/validations.store';
import {
  alignerAnimateurs,
  alignerStands,
  filterComparedAnimateurs,
  filterComparedStands,
  formatDelta,
  JourEvenement,
  JourneeViewComparable,
  lignesSynthese,
  SummaryScope,
  syntheseJournee,
} from './journee';
import { RouterLink } from '@angular/router';

/**
 * Two days of the persisted plan side by side (the Journée page's comparison
 * mode): a banner of figures with the écart B − A, then aligned lines — one
 * per stand in the calendar reading, one per animateur in the rail reading —
 * the same order on both sides, a closed stand or an absent animateur greyed
 * on the side where it is missing.
 *
 * <p>Read-only on purpose: a drag on a two-day screen would be ambiguous, so
 * this view draws no handle and no drop target at all — leaving the
 * comparison brings the renderings, and their drag and drop, back.</p>
 *
 * <p>No request of its own: the page already holds the whole plan, and a
 * comparison is a second slicing of it.</p>
 */
@Component({
  selector: 'app-comparaison-vue',
  imports: [
    FormsModule,
    MatButtonToggleModule,
    MatCardModule,
    MatCheckboxModule,
    MatIconModule,
    NgTemplateOutlet,
    RouterLink,
  ],
  templateUrl: './comparaison-vue.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparaisonView {
  readonly planning = input<PlanningEvenement | null>(null);
  readonly pauses = input<RapportPauses | null>(null);
  readonly jourA = input.required<JourEvenement>();
  readonly jourB = input.required<JourEvenement>();
  readonly view = input<JourneeViewComparable>('calendrier');
  /** The page's shared filters, applied to both days at once. */
  readonly filtre = input('');
  readonly stand = input('');
  readonly animateur = input('');
  /** « Seulement les différences » — the view state this rendering owns (`ecarts`). */
  readonly seulementEcarts = model(false);

  private readonly validations = inject(ValidationsStore);

  /**
   * Which day a narrow screen shows: the two columns do not fit a phone, so
   * they fold into two tabs. A chrome detail of this screen, not view state.
   */
  protected readonly onglet = signal<'a' | 'b'>('a');

  private readonly postes = computed(() => this.planning()?.postes ?? []);

  /** True when a filter narrows the lines: the banner then sums up those lines only, and says so. */
  protected readonly filtree = computed(
    () =>
      this.filtre().trim() !== '' ||
      this.stand() !== '' ||
      this.animateur() !== '' ||
      this.seulementEcarts(),
  );

  /** The banner, over the same lines as the table under it. */
  protected readonly synthese = computed(() => {
    let scope: SummaryScope | null = null;
    if (this.filtree()) {
      scope =
        this.view() === 'rail'
          ? { animateurIds: new Set(this.lignesAnimateurs().map((ligne) => ligne.animateurId)) }
          : { standIds: new Set(this.lignesStands().map((ligne) => ligne.standId)) };
    }
    return lignesSynthese(
      syntheseJournee(this.postes(), this.jourA().jour, this.pauses(), scope),
      syntheseJournee(this.postes(), this.jourB().jour, this.pauses(), scope),
    );
  });

  private readonly filtres = computed(() => ({
    filtre: this.filtre(),
    stand: this.stand(),
    animateur: this.animateur(),
    seulementEcarts: this.seulementEcarts(),
  }));

  private readonly allStandLines = computed(() =>
    alignerStands(this.postes(), this.jourA().jour, this.jourB().jour),
  );
  protected readonly lignesStands = computed(() =>
    filterComparedStands(this.allStandLines(), this.filtres()),
  );
  private readonly allAnimateurLines = computed(() =>
    alignerAnimateurs(this.postes(), this.jourA().jour, this.jourB().jour),
  );
  protected readonly lignesAnimateurs = computed(() =>
    filterComparedAnimateurs(this.allAnimateurLines(), this.filtres()),
  );

  /** How many lines differ, before any filter: the count the switch announces. */
  protected readonly deltaCount = computed(() =>
    this.view() === 'rail'
      ? this.allAnimateurLines().filter((ligne) => ligne.different).length
      : this.allStandLines().filter((ligne) => ligne.different).length,
  );
  /** True when the unfiltered comparison has no line at all — nothing to compare, not a filter at work. */
  protected readonly aucuneLigne = computed(() =>
    this.view() === 'rail'
      ? this.allAnimateurLines().length === 0
      : this.allStandLines().length === 0,
  );

  protected readonly relueA = computed(() => this.isReviewed(this.jourA()));
  protected readonly relueB = computed(() => this.isReviewed(this.jourB()));

  protected readonly formatDelta = formatDelta;
  protected readonly formatDuration = formatDuration;
  protected readonly ongletsLabel = $localize`:@@journee.comparaison.onglets:Journée affichée`;

  private isReviewed(jour: JourEvenement): boolean {
    return jour.date !== null && this.validations.acceptedDays().has(jour.date);
  }
}
