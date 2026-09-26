import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AnalysesApi } from '../../core/api/analyses-api';
import { errorPrefix } from '../../core/error-message';
import { FenetreIntendance, JourneeIntendance, RapportIntendance } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { formatHeure } from '../../core/time-of-day';
import { OutputPanel } from '../../shared/output-panel';

/** One cell of the table: how many people are out, and how many of them are minors. */
export interface CelluleIntendance {
  personnes: number;
  mineurs: number;
  /** Empty when nobody is out — the tooltip only has something to say otherwise. */
  detail: string;
}

/** One emplacement's row, hour by hour. */
export interface LigneIntendance {
  emplacementNom: string;
  cellules: CelluleIntendance[];
  total: number;
  totalMineurs: number;
}

/** One window of one day, ready to render: hourly bands across, emplacements down. */
export interface TableauIntendance {
  titre: string;
  fenetre: string;
  tranches: string[];
  lignes: LigneIntendance[];
  total: number;
  totalMineurs: number;
  /** Column totals, so « entre 13 et 14 h, 24 personnes » reads without adding up the rows. */
  slotTotals: number[];
}

/**
 * The tables the screen draws, from the report: one per window of each day.
 * A pure function, so the grouping and the wording are tested without
 * rendering anything.
 */
export function tableauxIntendance(rapport: RapportIntendance | null): TableauIntendance[] {
  const tableaux: TableauIntendance[] = [];
  const pasMinutes = rapport?.pasMinutes ?? 60;
  for (const journee of rapport?.journees ?? []) {
    for (const fenetre of journee.fenetres) {
      tableaux.push(tableau(journee, fenetre, pasMinutes));
    }
  }
  return tableaux;
}

/**
 * A column heading names the band, not its start: « 12–13 » is what somebody
 * organising a service reads, « 12:00 » is a moment and answers nothing. The
 * end comes from the report's own step, so a band that stops being an hour
 * renames itself.
 */
function bande(debut: string, pasMinutes: number): string {
  const [heures, minutes] = debut.split(':').map(Number);
  const fin = (heures * 60 + minutes + pasMinutes) % (24 * 60);
  const finHeure = String(Math.floor(fin / 60)).padStart(2, '0');
  const finMinute = String(fin % 60).padStart(2, '0');
  const finFormatee = formatHeure(`${finHeure}:${finMinute}`);
  return `${formatHeure(debut)}–${finFormatee}`;
}

function tableau(
  journee: JourneeIntendance,
  fenetre: FenetreIntendance,
  pasMinutes: number,
): TableauIntendance {
  const tranches = fenetre.tranches.map((tranche) => bande(tranche, pasMinutes));
  const slotTotals = tranches.map((_, index) =>
    fenetre.emplacements.reduce((somme, ligne) => somme + (ligne.personnes[index] ?? 0), 0),
  );
  return {
    titre: journee.date,
    fenetre: `${fenetre.libelle} · ${formatHeure(fenetre.debut)}–${formatHeure(fenetre.fin)}`,
    tranches,
    lignes: fenetre.emplacements.map((ligne) => ({
      emplacementNom: ligne.emplacementNom,
      cellules: ligne.personnes.map((personnes, index) => ({
        personnes,
        mineurs: ligne.mineurs[index] ?? 0,
        detail: libelleCellule(personnes, ligne.mineurs[index] ?? 0),
      })),
      total: ligne.total,
      totalMineurs: ligne.totalMineurs,
    })),
    total: fenetre.total,
    totalMineurs: fenetre.totalMineurs,
    slotTotals,
  };
}

function libelleCellule(personnes: number, mineurs: number): string {
  if (personnes === 0) {
    return '';
  }
  return mineurs === 0
    ? $localize`:@@intendance.cell:${personnes}:personnes: personne(s) en coupure repas`
    : $localize`:@@intendance.cellMineurs:${personnes}:personnes: personne(s) en coupure repas, dont ${mineurs}:mineurs: mineur(s)`;
}

/**
 * « Intendance des repas » (issue #598), under the breaks of the Planning
 * page's « Pauses et repas » (issue #712): how many people are out for their
 * meal on the day on screen, when, and where — « combien de sandwichs
 * préparer, et où les porter ».
 *
 * The breaks above answer animateur by animateur, the right reading to
 * organise a relay and the wrong one to prepare trays: this is the same meal
 * breaks, counted hour by hour and per emplacement, from
 * `GET /api/pauses/intendance`, read once and narrowed to the day. The whole
 * event, every day of it, leaves as a CSV. A read-out of the persisted plan,
 * never a solve.
 *
 * Nobody is named: the intendance needs a headcount and a place, and a minor
 * is counted, never identified — no name, no birth date (`docs/rgpd.md` §7).
 */
@Component({
  selector: 'app-intendance-jour',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MatTooltipModule, OutputPanel],
  templateUrl: './intendance-jour.html',
  // The table is the heatmap's, down to its cells: same shape, same reading, so
  // it wears the same stylesheet rather than a second copy of it; what this
  // table does differently is next door.
  styleUrls: ['../../../styles/heatmap.css', './intendance-jour.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the heatmap.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class IntendanceJour {
  /** The day on screen, `AAAA-MM-JJ`; null shows nothing. */
  readonly date = input<string | null>(null);

  private readonly analyses = inject(AnalysesApi);

  private readonly lecture = resource({ loader: () => this.analyses.intendance() });
  protected readonly busy = this.lecture.isLoading;
  protected readonly erreur = errorText(this.lecture);
  protected readonly exportBusy = signal(false);
  protected readonly output = signal('');

  private readonly rapport = computed<RapportIntendance | null>(() =>
    this.lecture.hasValue() ? this.lecture.value() : null,
  );
  /** The tables of the day on screen: its meal windows, midday and evening. */
  protected readonly tableaux = computed(() =>
    tableauxIntendance(this.rapport()).filter((tableau) => tableau.titre === this.date()),
  );
  /** The server's own sentence when there is nothing to show; empty otherwise. */
  protected readonly message = computed(() => this.rapport()?.message ?? '');
  protected readonly aucuneDonnee = computed(() => (this.rapport()?.journees.length ?? 0) === 0);

  /** The whole event, every day and every window, as the CSV the intendance prints. */
  protected async exporter(): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@intendance.exporting:Construction de l'export CSV...`);
    try {
      this.output.set(await this.analyses.exportIntendance());
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
    }
  }
}
