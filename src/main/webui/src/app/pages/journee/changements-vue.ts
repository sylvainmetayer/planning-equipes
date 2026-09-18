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
import { ChangementsReading, isUnchanged, referenceParam, typeSiegeLabel } from './changements';

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

  private readonly changements = resource({
    params: () => ({ jour: this.jour(), reference: this.reference() }),
    loader: ({ params }) =>
      params.jour
        ? this.api.changements(params.jour, params.reference)
        : Promise.resolve(undefined),
  });
  /** Kept across a failed refresh; the failure shows in its place, not a blank card. */
  protected readonly changes = retainedValue(this.changements);
  protected readonly loading = this.changements.isLoading;
  protected readonly error = errorText(this.changements);

  /** What the toggle shows: the reference the answer was computed against. */
  protected readonly referenceShown = computed<ReferenceChangementsParam | null>(() => {
    const changes = this.changes();
    return changes ? referenceParam(changes.reference) : this.reference();
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
    const quand = new Date(changes.referenceLe).toLocaleString(intlLocale());
    return changes.reference === 'PUBLICATION'
      ? $localize`:@@journee.changements.depuisPublication:Depuis la publication du ${quand}:date:`
      : $localize`:@@journee.changements.depuisResolution:Depuis la résolution du ${quand}:date:`;
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
