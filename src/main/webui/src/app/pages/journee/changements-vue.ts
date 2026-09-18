import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  resource,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { JourneesApi, ReferenceChangementsParam } from '../../core/api/journees-api';
import { heureCourte } from '../../core/horaire-stand';
import { intlLocale } from '../../core/locale';
import { ChangementSiege, TypeChangementSiege } from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import {
  ChangementsReading,
  filterPersonLines,
  filterSeatLines,
  isUnchanged,
  referenceParam,
  typeSiegeLabel,
} from './changements';

/**
 * « Changements » : what moved on the day on screen since a reference — the
 * last publication, or the plan the last solve started from — read two ways
 * over the same facts: seat by seat for whoever runs the stands, person by
 * person for whoever will warn the animateurs, in the very sentences the
 * publication mail would carry.
 *
 * <p>The reference and the reading are view state, handed over two-way and
 * written to the URL by the page. A reference nobody chose is left to the
 * server, which picks the publication when one exists; the answer says which,
 * and that is what the toggle shows.</p>
 */
@Component({
  selector: 'app-changements-vue',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
  ],
  templateUrl: './changements-vue.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChangementsView {
  private readonly api = inject(JourneesApi);

  /** The day on screen, `AAAA-MM-JJ`; null while the plan is still loading, or on an undated day. */
  readonly jour = input<string | null>(null);
  /** The reference the URL names; null when nobody chose, which the server resolves. */
  readonly reference = model<ReferenceChangementsParam | null>(null);
  readonly reading = model<ChangementsReading>('vacations');
  /** The page's shared filters, applied to both readings. */
  readonly recherche = input('');
  readonly stand = input('');
  readonly animateur = input('');
  /** The stands of the plan, to name the one the stand filter selects in the per-person sentences. */
  readonly standNoms = input<ReadonlyMap<string, string>>(new Map());

  private readonly changements = resource({
    params: () => ({ jour: this.jour(), reference: this.reference() }),
    loader: ({ params }) =>
      params.jour
        ? this.api.changements(params.jour, params.reference)
        : Promise.resolve(undefined),
  });
  // Retained across a re-key so the card keeps its figures while the other
  // reference loads; a refusal clears it, since figures under an error line
  // could belong to another day.
  private readonly retained = retainedValue(this.changements);
  protected readonly changes = computed(() =>
    this.changements.status() === 'error' ? null : this.retained(),
  );
  protected readonly loading = this.changements.isLoading;
  protected readonly error = errorText(this.changements);

  /**
   * What the toggle shows: the reference chosen, at once — a toggle that waits
   * for the answer reads as a dead button — and, when nobody chose, the one
   * the answer was computed against.
   */
  protected readonly referenceShown = computed<ReferenceChangementsParam | null>(() => {
    const changes = this.changes();
    return this.reference() ?? (changes ? referenceParam(changes.reference) : null);
  });
  protected readonly seatLines = computed(() =>
    filterSeatLines(
      this.changes()?.parVacation ?? [],
      this.recherche(),
      this.stand(),
      this.animateur(),
    ),
  );
  protected readonly personLines = computed(() =>
    filterPersonLines(
      this.changes()?.parAnimateur ?? [],
      this.recherche(),
      this.standNoms().get(this.stand()) ?? this.stand(),
      this.animateur(),
    ),
  );
  /** True when the day did change, but nothing of it matches the page's filters. */
  protected readonly nothingMatches = computed(() => {
    const changes = this.changes();
    if (!changes?.referenceDisponible || isUnchanged(changes)) {
      return false;
    }
    return this.reading() === 'animateurs'
      ? this.personLines().length === 0
      : this.seatLines().length === 0;
  });
  protected readonly unchanged = computed(() => {
    const changes = this.changes();
    return changes !== null && isUnchanged(changes);
  });
  /** « depuis la publication du … » — the reference, dated. */
  protected readonly referenceLabel = computed(() => {
    const changes = this.changes();
    if (!changes?.referenceLe) {
      return '';
    }
    const formattedDate = new Date(changes.referenceLe).toLocaleString(intlLocale());
    return changes.reference === 'PUBLICATION'
      ? $localize`:@@journee.changements.depuisPublication:Depuis la publication du ${formattedDate}:date:`
      : $localize`:@@journee.changements.depuisResolution:Depuis la résolution du ${formattedDate}:date:`;
  });

  protected chooseReference(reference: ReferenceChangementsParam): void {
    this.reference.set(reference);
  }

  protected chooseReading(reading: ChangementsReading): void {
    this.reading.set(reading);
  }

  protected hours(ligne: ChangementSiege): string {
    return `${heureCourte(ligne.heureDebut)} – ${heureCourte(ligne.heureFin)}`;
  }

  protected typeLabel(type: TypeChangementSiege): string {
    return typeSiegeLabel(type);
  }
}
