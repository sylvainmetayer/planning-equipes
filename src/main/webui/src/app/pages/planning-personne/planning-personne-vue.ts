import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  model,
  output,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import {
  DUREE_HEBDOMADAIRE_MAX_HEURES,
  DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES,
  HoursSource,
  PlanningEvenement,
} from '../../core/models';
import { errorText, retainedValue } from '../../core/resource-state';
import { NO_SORT, SortState } from '../../core/view-query-params';
import { OutputPanel } from '../../shared/output-panel';
import { StatusMessage } from '../../shared/status-message';
import { JourEvenement } from '../journee/journee';
import {
  COLOUR_NOTE_ID,
  GridSpan,
  joursGrille,
  sliceDays,
  spanWindow,
} from '../planning-grille/jours-grille';
import { GridSpanToggle } from '../planning-grille/grid-span-toggle';
import {
  CaseActivee,
  GrilleCase,
  GrilleEntete,
  LigneGrille,
  PlanningGrille,
  TRI_NOM,
} from '../planning-grille/planning-grille';
import {
  buildTableauPersonnes,
  CasePersonne,
  DensitePersonne,
  LignePersonne,
  PersonView,
} from './planning-personne';

/**
 * « Par personne » (issue #713): one line per animateur, one column per day,
 * the stand and the hours in the cell, coloured work / rest / unavailable —
 * the other sheet an organiser coming from a spreadsheet keeps — and at the
 * right what a spreadsheet does not compute: the hours and their distance to
 * the median, the evening, week-end, holiday, Sunday and « Nuit (paie) »
 * hours, the demanding seats, the variety, the wishes honoured, the days
 * worked and off, the longest run. Every column sorts; the columns every line
 * leaves at zero are hidden until asked for. The frise, one proportional bar
 * per person, is its second rendering.
 *
 * <p>It gathers five screens: Heures, Équité, Jours de repos and the
 * animateur mode of the Heatmap, whose two CSV files it keeps. There is one
 * evening in the application — the settable one of the Équité report; the
 * payroll's fixed 22:00 survives as « Nuit (paie) » only. The plan is the
 * page's; the two reports are read here, once per visit, by the server: the
 * Heures on the plan the « Plan publié / Plan enregistré » toggle names — what
 * the payroll is paid on, the publication, as soon as there is one — and every
 * hour column of the synthesis with it; the Équité, for the counts it alone
 * carries, on the persisted plan, like the cells of the grid.</p>
 *
 * <p>A cell opens the Siège panel on the first seat of that person that day;
 * the name leads to the fiche.</p>
 */
@Component({
  selector: 'app-planning-personne-vue',
  imports: [
    DatePipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatCheckboxModule,
    MatIconModule,
    MatProgressBarModule,
    RouterLink,
    OutputPanel,
    StatusMessage,
    PlanningGrille,
    GrilleEntete,
    GrilleCase,
    GridSpanToggle,
  ],
  templateUrl: './planning-personne-vue.html',
  styleUrl: './planning-personne-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanningPersonneView {
  private readonly planningApi = inject(PlanningApi);

  readonly planning = input<PlanningEvenement | null>(null);
  readonly jours = input<readonly JourEvenement[]>([]);
  /** The day the page is on, marked in the grid. */
  readonly jourMarque = input<string | null>(null);
  /** The page's filters: the stands its stand, location and game-category filters leave, a person, a text. */
  readonly standsRetenus = input<ReadonlySet<string> | null>(null);
  readonly animateur = input('');
  readonly filtre = input('');
  /* The view state, kept by the page in the URL: `vue` is switched in its header, the rest here. */
  readonly view = input<PersonView>('grille');
  readonly densite = model<DensitePersonne>('detail');
  /** How many days the grid shows around the marked one (`portee`); the frise always draws the event. */
  readonly span = model<GridSpan>('semaine');
  readonly tri = model<SortState>(NO_SORT);
  readonly allColumns = model(false);
  readonly noRestOnly = model(false);
  /**
   * Which plan the hours are read from (`source`): the publication in force or
   * the persisted plan. Null leaves the choice to the server — the publication
   * when there is one, the persisted plan otherwise.
   */
  readonly hoursSource = model<HoursSource | null>(null);
  /** A cell was opened: the page opens the Siège panel on the seat, and moves to its day. */
  readonly seatRequested = output<{ posteId: string; jour: string }>();

  /** The Équité report: under today's legal parameters, the evening included. */
  private readonly equite = resource({ loader: () => this.planningApi.equityReport() });
  /**
   * What the Heures report is read for: the source the toggle names, and the
   * page's plan — so a gesture that reloads it reloads the hours of the
   * persisted plan too. Not under « Plan publié »: the publication does not
   * move when the working plan does, and re-reading it would only cost a
   * request. Equal while both are, so an unrelated change reads nothing.
   */
  private readonly hoursParams = computed(
    () => {
      const planning = this.planning();
      const source = this.hoursSource();
      return planning ? { source, planning: source === 'publie' ? null : planning } : undefined;
    },
    { equal: (a, b) => a?.source === b?.source && a?.planning === b?.planning },
  );
  /**
   * The Heures report — every hour column of the synthesis, the weeks and the
   * total included — read by the server on the plan the toggle names, never on
   * the one this page holds: the table and the payroll CSV then come from one
   * report.
   */
  private readonly heures = resource({
    params: () => this.hoursParams(),
    loader: ({ params }) => this.planningApi.hoursReport(params.source),
  });
  /**
   * The reading on screen: which plan it was, its date, whether a publication
   * exists — the last one kept while the next loads, so a switch or a reload
   * blanks no column and greys no button for the time of a request.
   */
  protected readonly hoursReading = retainedValue(this.heures);
  /** The source the toggle shows: the one asked for, else the one the server chose. */
  protected readonly shownSource = computed<HoursSource | null>(
    () => this.hoursSource() ?? this.hoursReading()?.source ?? null,
  );
  /**
   * Under « Plan publié » the hour columns are the publication's, but the
   * cells of the grid, the frise and the counts (seats, stands, week-end…)
   * are the persisted plan's: said on screen rather than left to guess.
   */
  protected readonly mixedSources = computed(() => this.hoursReading()?.source === 'publie');
  /** « Plan publié » is offered once the server said a publication exists. */
  protected readonly publicationAvailable = computed(
    () => this.hoursReading()?.publicationAvailable ?? false,
  );
  protected readonly chargement = computed(
    () => this.equite.isLoading() || this.heures.isLoading(),
  );
  private readonly erreurEquite = errorText(this.equite);
  private readonly erreurHeures = errorText(this.heures);
  protected readonly erreur = computed(() => this.erreurEquite() || this.erreurHeures());

  protected readonly exportBusy = signal(false);
  protected readonly output = signal('');

  protected readonly colonnesJours = computed(() => joursGrille(this.jours()));
  protected readonly tableau = computed(() =>
    buildTableauPersonnes(
      this.planning(),
      this.jours(),
      this.equite.hasValue() ? this.equite.value() : null,
      this.hoursReading()?.report ?? null,
      {
        animateur: this.animateur(),
        standsRetenus: this.standsRetenus(),
        recherche: this.filtre(),
        sansReposSeulement: this.noRestOnly(),
        toutesColonnes: this.allColumns(),
        // The day grid shows the names alone: a summary column's sort, kept for
        // the synthesis, would order its lines by nothing on screen.
        tri: this.view() === 'grille' && this.tri().active !== TRI_NOM ? NO_SORT : this.tri(),
      },
    ),
  );

  private readonly dayWindow = computed(() =>
    spanWindow(this.colonnesJours(), this.jourMarque(), this.span()),
  );
  protected readonly visibleColumns = computed(() =>
    this.colonnesJours().slice(this.dayWindow().start, this.dayWindow().end),
  );
  /** The days of the span, and nothing else: the summary is the synthesis's. */
  protected readonly daysTable = computed(() => sliceDays(this.tableau(), this.dayWindow()));
  /** The summary alone, over the whole event: no day column, the totals of the people shown. */
  protected readonly synthesisTable = computed(() => {
    const table = sliceDays(this.tableau(), { start: 0, end: 0 });
    return {
      ...table,
      pied: {
        ...table.pied,
        libelle: $localize`:@@planningPersonne.footer.synthese:Total des personnes affichées`,
      },
    };
  });

  /**
   * Over the whole event a cell holds its colour alone: sixteen columns of
   * stands and hours do not fit a screen, the colours do. The chosen density
   * is kept for the week and the day.
   */
  protected readonly colourForced = computed(() => this.span() === 'evenement');
  protected readonly effectiveDensite = computed<DensitePersonne>(() =>
    this.colourForced() ? 'compact' : this.densite(),
  );
  protected readonly colourNoteId = COLOUR_NOTE_ID;

  /** `20:00`, the start of the evening every screen reads. */
  protected readonly heureSoiree = computed(() =>
    this.equite.hasValue() ? this.equite.value().heureDebutSoiree.slice(0, 5) : '',
  );

  /** The two weekly ceilings a week column is flagged against. */
  protected readonly adultCeiling = DUREE_HEBDOMADAIRE_MAX_HEURES;
  protected readonly minorCeiling = DUREE_HEBDOMADAIRE_MAX_MINEUR_HEURES;

  protected readonly noPublicationLabel = $localize`:@@planningPersonne.source.aucunePublication:Rien n'a encore été publié : seules les heures du plan enregistré existent`;
  protected readonly noRestLabel = $localize`:@@repos.row.sansRepos:Aucun jour de repos sur tout l'événement`;
  protected readonly ficheLabel = $localize`:@@planningPersonne.fiche:Ouvrir la fiche de la personne`;

  protected personne(ligne: LigneGrille): LignePersonne {
    return ligne as LignePersonne;
  }

  protected cellOf(ligne: LigneGrille, index: number): CasePersonne {
    return (ligne as LignePersonne).cases[index];
  }

  protected open(event: CaseActivee<LignePersonne>): void {
    const posteId = event.ligne.cases[this.visibleColumns().indexOf(event.jour)]?.posteId;
    if (posteId) {
      this.seatRequested.emit({ posteId, jour: event.jour.key });
    }
  }

  /**
   * « Heures pour la paie »: the weeks, the total, Sunday, holidays and the
   * night past 22:00, of the plan the toggle names — the one the hour columns
   * of the synthesis are read on, from the same report.
   */
  protected async exporterHeures(): Promise<void> {
    const source = this.shownSource();
    await this.exporter(() => this.planningApi.exportHours(source));
  }

  /** « Équité »: every column of the report, with its synthesis. */
  protected async exporterEquite(): Promise<void> {
    await this.exporter(() => this.planningApi.exportEquity());
  }

  private async exporter(telecharger: () => Promise<string>): Promise<void> {
    this.exportBusy.set(true);
    this.output.set($localize`:@@hours.exporting:Construction de l'export CSV...`);
    try {
      this.output.set(await telecharger());
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.exportBusy.set(false);
    }
  }
}
