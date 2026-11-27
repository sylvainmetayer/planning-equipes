import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  model,
  ViewEncapsulation,
} from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatTooltipModule } from '@angular/material/tooltip';
import { RapportPauses } from '../../core/models';
import { formatHeure } from '../../core/time-of-day';
import {
  coupuresRepasJournee,
  GroupeStand,
  standGroupsOfDay,
  JourPauses,
  reportDays,
  libelleRelais,
  LigneCoupureRepas,
  LignePausePlanifiee,
  plannedOfDay,
  daySummary,
} from './pauses';
import { RouterLink } from '@angular/router';

/**
 * « Pauses et repas » : where the legal breaks fall, day by day and stand by
 * stand — who steps out at the latest when, and who is there to cover.
 * The meals counted per emplacement for the intendance
 * (`pages/intendance/intendance-jour`) are the tab's next section, not this view's.
 *
 * A section of the Diagnostic's « Repas et pauses » tab (taken back from the
 * Planning page's former « Pauses » view): it lives inside a day, where relays
 * are organised, not over the whole event. Everything shown
 * comes from `GET /api/pauses`, read once by the page and handed over —
 * computed server-side from the persisted plan under the organiser's current
 * legal parameters and meal windows, and no solve is launched, here or there.
 *
 * The meal break sits on this view next to the legal ones, and is read by
 * the very same calculation the solver scores (`CoupureRepas`): two screens
 * telling two stories about the same day is exactly the failure issue #438
 * describes.
 */
@Component({
  selector: 'app-pauses-vue',
  imports: [MatCardModule, MatCheckboxModule, MatIconModule, MatTooltipModule, RouterLink],
  templateUrl: './pauses-vue.html',
  styleUrl: './pauses-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PausesView {
  readonly rapport = input<RapportPauses | null>(null);
  /** ISO date the tab selected; the first day the report covers when null. */
  readonly date = input<string | null>(null);
  /** The page's shared filters: a name or stand text, a stand id, an animateur id — each empty when unset. */
  readonly recherche = input('');
  readonly stand = input('');
  readonly animateur = input('');
  /** The two narrowing switches — the view state this rendering owns (`relais`, `repas`). */
  readonly withoutRelaisOnly = model(false);
  /** Narrows the meal-break section to the days that have no room for one. */
  readonly coupuresManquantesSeulement = model(false);
  protected readonly jours = computed<JourPauses[]>(() => reportDays(this.rapport()));
  /**
   * The day the tab selected, when the report knows it. The selector lists
   * every day of the plan while the report only knows the days somebody works
   * long enough to owe a break: falling back on the first day of the report
   * then showed another day's breaks under the current day's heading, with
   * nothing on screen to give it away. A day the report does not cover has no
   * rows, and the empty message says so.
   *
   * <p>No day at all — nobody asked for one — still opens on the first day the
   * report covers.</p>
   */
  protected readonly jourCourant = computed<JourPauses | null>(() => {
    const jours = this.jours();
    const date = this.date();
    return date === null ? (jours[0] ?? null) : (jours.find((jour) => jour.date === date) ?? null);
  });
  protected readonly groupes = computed<GroupeStand[]>(() => {
    const stand = this.stand();
    const animateur = this.animateur();
    return standGroupsOfDay(
      this.rapport(),
      this.jourCourant()?.date ?? null,
      this.recherche(),
      this.withoutRelaisOnly(),
    )
      .filter((groupe) => !stand || groupe.standId === stand)
      .map((groupe) =>
        animateur
          ? { ...groupe, lignes: groupe.lignes.filter((ligne) => ligne.animateurId === animateur) }
          : groupe,
      )
      .filter((groupe) => groupe.lignes.length > 0);
  });
  protected readonly planifiees = computed<LignePausePlanifiee[]>(() =>
    plannedOfDay(this.rapport(), this.jourCourant()?.date ?? null, this.recherche()).filter(
      (pause) => !this.animateur() || pause.animateurId === this.animateur(),
    ),
  );
  protected readonly coupuresRepas = computed<LigneCoupureRepas[]>(() =>
    coupuresRepasJournee(
      this.rapport(),
      this.jourCourant()?.date ?? null,
      this.recherche(),
      this.coupuresManquantesSeulement(),
    ).filter((coupure) => !this.animateur() || coupure.animateurId === this.animateur()),
  );
  protected readonly synthese = computed(() =>
    daySummary(this.rapport(), this.jourCourant()?.date ?? null),
  );
  protected readonly heure = formatHeure;
  protected readonly libelleRelais = libelleRelais;
}
