import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../core/api.service';
import { PlanningStateService } from '../../core/planning-state.service';
import { PlanningEvenement, TypologieItem } from '../../core/models';
import { correspondAuFiltre } from '../../core/text-filter';
import { typologieColorClass, typologieLabel, typologieLabels } from '../../core/typologie-colors';
import { errorPrefix } from '../../core/error-message';
import { keepViewInQueryParams, optionalParam } from '../../core/view-query-params';
import { TableFilter } from '../../shared/table-filter';
import { RailJour, RailLigne, buildRailJours, compterStatuts } from './rail-jour';

/** Which lines the rail keeps: everyone, only the mobilisable ones, only the working ones. */
export type RailVue = 'tous' | 'libres' | 'affectes';

/** One entry of the typologie colour legend shown above the rail. */
interface RailLegendItem {
  id: string;
  label: string;
  colorClass: string;
}

/**
 * Day "rail" (issue #305): one line per animateur of the edition, time on the
 * x axis, vacations as positioned blocks. The dual of `calendar-day-page.ts`,
 * which lists the same day stand by stand — here the gaps, the daily spans and
 * the back-to-back chains show at a glance, and so do the people not working
 * at all, which is what a day of tension needs.
 *
 * Same read-only source and pure-builder pattern as the other views
 * (`planningState.loadForDisplay()` + `buildRailJours()`): no dedicated
 * endpoint, and never a solve.
 */
@Component({
  selector: 'app-rail-jour-page',
  imports: [
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    TableFilter
  ],
  templateUrl: './rail-jour-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class RailJourPage {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly planning = signal<PlanningEvenement | null>(null);
  /** Typologie referential, only used to turn ids into legend labels. */
  protected readonly typologies = signal<TypologieItem[]>([]);
  /** Day the rail shows; null until the plan is loaded, then the first day of the event. */
  protected readonly jourSelectionne = signal<number | null>(null);
  protected readonly filtre = signal('');
  protected readonly vue = signal<RailVue>('tous');

  private readonly api = inject(ApiService);
  private readonly planningState = inject(PlanningStateService);
  private readonly route = inject(ActivatedRoute);
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly jours = computed<RailJour[]>(() => {
    const planning = this.planning();
    return planning ? buildRailJours(planning.postes ?? [], planning.animateurs ?? []) : [];
  });

  /**
   * The day actually displayed. Resolved rather than corrected by an effect: a
   * `jour` from the URL naming a day the plan no longer holds falls back to the
   * first one instead of leaving the page blank.
   */
  protected readonly jourCourant = computed<RailJour | null>(() => {
    const jours = this.jours();
    const selectionne = this.jourSelectionne();
    return jours.find((jour) => jour.jour === selectionne) ?? jours[0] ?? null;
  });

  protected readonly lignes = computed<RailLigne[]>(() => this.jourCourant()?.lignes ?? []);

  protected readonly lignesAffichees = computed<RailLigne[]>(() => {
    const vue = this.vue();
    const filtre = this.filtre();
    return this.lignes().filter((ligne) => {
      if (vue === 'libres' && ligne.statut !== 'libre') {
        return false;
      }
      if (vue === 'affectes' && ligne.statut !== 'affecte') {
        return false;
      }
      return correspondAuFiltre(filtre, [ligne.nom]);
    });
  });

  protected readonly compteurs = computed(() => compterStatuts(this.lignes()));

  protected readonly compteursLabel = computed(() => {
    const { affectes, libres, indisponibles } = this.compteurs();
    const total = this.lignes().length;
    return $localize`:@@railJour.counters:${total}:total: animateur(s) : ${affectes}:affectes: affecté(s), ${libres}:libres: mobilisable(s), ${indisponibles}:indisponibles: indisponible(s)`;
  });

  /** Typologies actually present on the displayed day, so the legend only explains colours that are on screen. */
  protected readonly legende = computed<RailLegendItem[]>(() => {
    const labels = typologieLabels(this.typologies());
    const ids = new Set<string>();
    this.lignes().forEach((ligne) =>
      ligne.blocs.forEach((bloc) => {
        if (bloc.typologie) {
          ids.add(bloc.typologie);
        }
      })
    );
    return Array.from(ids)
      .map((id) => ({ id, label: typologieLabel(labels, id), colorClass: typologieColorClass(id) }))
      .sort((left, right) => left.label.localeCompare(right.label));
  });

  /**
   * Width of one hour of the rail, as a background-size: the gridlines are one
   * repeating background instead of one element per hour and per line — at 150
   * lines and a fifteen-hour day that would be 2 250 divs drawing nothing.
   */
  protected readonly gridSize = computed(() => {
    const jour = this.jourCourant();
    if (!jour) {
      return '100% 100%';
    }
    const heures = Math.max(1, (jour.finMinutes - jour.debutMinutes) / 60);
    return `${100 / heures}% 100%`;
  });

  /** True as soon as the filters differ from the ones this page opens on — the day itself is navigation, not a filter. */
  protected readonly vueModifiee = computed(() => this.vue() !== 'tous' || this.filtre().trim() !== '');

  /**
   * The line the grid hands the focus to (roving tabindex): one stop for the
   * whole rail on Tab, then the arrows walk the lines. Clamped on read against
   * the displayed lines — filtering out the line it pointed at would otherwise
   * leave no cell in the tab order at all.
   */
  private readonly ligneFocus = signal(0);

  protected readonly ligneCourante = computed(() => {
    const lignes = this.lignesAffichees();
    if (lignes.length === 0) {
      return 0;
    }
    return Math.min(Math.max(this.ligneFocus(), 0), lignes.length - 1);
  });

  protected readonly animateurColumnLabel = $localize`:@@railJour.column.animateur:Animateur`;
  protected readonly libreLabel = $localize`:@@railJour.statut.libre:Libre`;
  protected readonly indisponibleLabel = $localize`:@@railJour.statut.indisponible:Indisponible`;
  protected readonly chevauchementLabel = $localize`:@@railJour.chevauchement:Vacations qui se chevauchent : cet animateur est attendu à deux endroits en même temps`;
  protected readonly jourPrecedentLabel = $localize`:@@railJour.previousDay:Jour précédent`;
  protected readonly jourSuivantLabel = $localize`:@@railJour.nextDay:Jour suivant`;

  constructor() {
    const params = this.route.snapshot.queryParamMap;
    const jour = Number(params.get('jour'));
    this.jourSelectionne.set(Number.isFinite(jour) && jour > 0 ? jour : null);
    this.filtre.set(params.get('q') ?? '');
    const vue = params.get('vue');
    this.vue.set(vue === 'libres' || vue === 'affectes' ? vue : 'tous');
    void this.refresh();
    keepViewInQueryParams(() => {
      const courant = this.jourCourant();
      const premier = this.jours()[0];
      return {
        // The first day is the default, and a default is the absence of its param.
        jour: courant && premier && courant.jour !== premier.jour ? String(courant.jour) : null,
        q: optionalParam(this.filtre()),
        vue: this.vue() === 'tous' ? null : this.vue()
      };
    });
  }

  protected async refresh(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      const [planning, typologies] = await Promise.all([
        this.planningState.loadForDisplay(),
        // Labels only: a missing referential degrades the legend to raw ids
        // rather than failing the rail.
        this.api.get<TypologieItem[]>('/api/typologies').catch(() => [])
      ]);
      this.planning.set(planning);
      this.typologies.set(typologies);
    } catch (error) {
      this.planning.set(null);
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  protected selectionnerJour(jour: number): void {
    this.jourSelectionne.set(jour);
    this.ligneFocus.set(0);
  }

  /** Steps to the previous/next day of the event; a no-op at either end. */
  protected decalerJour(delta: number): void {
    const jours = this.jours();
    const index = jours.findIndex((jour) => jour.jour === this.jourCourant()?.jour);
    const cible = jours[index + delta];
    if (cible) {
      this.selectionnerJour(cible.jour);
    }
  }

  protected estPremierJour(): boolean {
    return this.jours()[0]?.jour === this.jourCourant()?.jour;
  }

  protected estDernierJour(): boolean {
    const jours = this.jours();
    return jours[jours.length - 1]?.jour === this.jourCourant()?.jour;
  }

  protected reinitialiserVue(): void {
    this.vue.set('tous');
    this.filtre.set('');
  }

  /** Keeps the roving tabindex on the line the user actually reached, mouse or keyboard. */
  protected focusLigne(index: number): void {
    this.ligneFocus.set(index);
  }

  protected naviguer(event: KeyboardEvent, index: number): void {
    const derniere = this.lignesAffichees().length - 1;
    let cible: number;
    switch (event.key) {
      case 'ArrowDown':
        cible = Math.min(index + 1, derniere);
        break;
      case 'ArrowUp':
        cible = Math.max(index - 1, 0);
        break;
      case 'Home':
        cible = 0;
        break;
      case 'End':
        cible = derniere;
        break;
      default:
        return;
    }
    event.preventDefault();
    this.ligneFocus.set(cible);
    this.hote.nativeElement.querySelector<HTMLElement>(`[data-ligne="${cible}"]`)?.focus();
  }
}
