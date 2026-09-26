import { NgTemplateOutlet } from '@angular/common';
import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  contentChild,
  Directive,
  effect,
  ElementRef,
  inject,
  Injector,
  input,
  model,
  output,
  signal,
  TemplateRef,
  untracked,
  ViewEncapsulation,
} from '@angular/core';
import { NO_SORT, SortState } from '../../core/view-query-params';

/** One day column of the grid: a day of the event, as the header draws it. */
export interface JourGrille {
  /** The page's key of the day (its date, or `J<n>`): what a click reports and `jourMarque` names. */
  key: string;
  /** `J5`. */
  label: string;
  /** Narrow weekday initial, empty on an undated plan. */
  initiale: string;
  /** `Jour 5 — 2026-09-05`, for the header's title and the cells' labels. */
  titre: string;
  weekEnd: boolean;
  /** First column of a week band — never the first column of the grid. */
  debutSemaine: boolean;
}

/** One cell: its class (the state it is coloured by), what it says aloud, and whether it opens something. */
export interface CaseGrille {
  classe: string;
  libelle: string;
  /** A cell that holds a seat opens the Siège panel; a rest day or a closed stand opens nothing. */
  active: boolean;
}

/** A value of a summary column: its text, and a class and a title when it has something to say. */
export interface ValeurSynthese {
  texte: string;
  classe?: string;
  titre?: string;
}

/** One line of the grid: a stand or an animateur, its day cells in the columns' order, its summary. */
export interface LigneGrille {
  id: string;
  cases: readonly CaseGrille[];
  synthese: Readonly<Partial<Record<string, ValeurSynthese>>>;
  /** A class for the whole row, e.g. somebody who never rests. */
  classe?: string;
}

/** A summary column at the right of the days. */
export interface ColonneSynthese {
  key: string;
  label: string;
  /** What the header's title adds: the unit, the solver rule that measures it. */
  titre?: string;
}

/** The footer: one figure per day, and one per summary column. */
export interface PiedGrille {
  libelle: string;
  cases: readonly string[];
  synthese: Readonly<Partial<Record<string, string>>>;
}

/** A cell was opened, by a click or by Enter. */
export interface CaseActivee<T extends LigneGrille> {
  ligne: T;
  jour: JourGrille;
}

/** The template of a row's header: a stand's or a person's name, as a link to its fiche. */
@Directive({ selector: 'ng-template[appGrilleEntete]' })
export class GrilleEntete {
  readonly template = inject<TemplateRef<{ $implicit: LigneGrille }>>(TemplateRef);
}

/** The template of a day cell's content: names, counters, a stand and its hours… or nothing. */
@Directive({ selector: 'ng-template[appGrilleCase]' })
export class GrilleCase {
  readonly template = inject<TemplateRef<{ $implicit: LigneGrille; index: number }>>(TemplateRef);
}

/** The key the first column sorts by. */
export const TRI_NOM = 'nom';

/**
 * The grid the Planning page's « Par stand » and « Par personne » axes share
 * (issue #713): one line per stand or per animateur, one column per day of the
 * event, summary columns at the right, a footer of totals. The first column
 * stays put while the days scroll under it, the headers too; the whole grid is
 * one tab stop, the arrows move inside it (roving tabindex, Home and End to
 * the ends of a line), and Enter or a click opens the cell — the page opens
 * the Siège panel on it. Sorting is the caller's: the grid says which column
 * was asked for, through `tri`.
 *
 * <p>Built on the Jours de repos grid (`pages/repos`), for the sixty-five
 * stands × sixteen days of the realistic scenario with their names in the
 * cells: one `<td>` per cell and a native `title` rather than a tooltip
 * directive, so a thousand cells stay a thousand elements. No virtualisation.</p>
 */
@Component({
  selector: 'app-planning-grille',
  imports: [NgTemplateOutlet],
  template: `
    <div class="table-wrapper planning-grille-defilement">
      <table class="planning-grille" role="grid">
        <caption class="visually-hidden">{{ caption() }}</caption>
        <thead>
          <tr>
            <th scope="col" class="planning-grille-entete" [attr.aria-sort]="ariaSort(triNom)">
              @if (triable()) {
                <button type="button" class="planning-grille-tri" (click)="sortBy(triNom)">
                  {{ header() }}<span aria-hidden="true">{{ fleche(triNom) }}</span>
                </button>
              } @else {
                {{ header() }}
              }
            </th>
            @for (jour of jours(); track jour.key) {
              <th
                scope="col"
                class="planning-grille-jour"
                [class.planning-grille-weekend]="jour.weekEnd"
                [class.planning-grille-semaine]="jour.debutSemaine"
                [class.planning-grille-marque]="jour.key === jourMarque()"
                [attr.aria-current]="jour.key === jourMarque() ? 'date' : null"
                [attr.aria-label]="jour.titre"
                [title]="jour.titre"
              >
                @if (jour.initiale) {
                  <span class="planning-grille-initiale">{{ jour.initiale }}</span>
                }
                <span class="planning-grille-numero">{{ jour.label }}</span>
              </th>
            }
            @for (colonne of syntheses(); track colonne.key) {
              <th scope="col" class="planning-grille-synthese" [attr.aria-sort]="ariaSort(colonne.key)"
                  [title]="colonne.titre ?? colonne.label">
                @if (triable()) {
                  <button type="button" class="planning-grille-tri" (click)="sortBy(colonne.key)">
                    {{ colonne.label }}<span aria-hidden="true">{{ fleche(colonne.key) }}</span>
                  </button>
                } @else {
                  {{ colonne.label }}
                }
              </th>
            }
          </tr>
        </thead>
        <tbody>
          @for (ligne of lignes(); track ligne.id; let indexLigne = $index) {
            <tr [class]="ligne.classe ?? ''">
              <th scope="row" class="planning-grille-entete">
                <ng-container [ngTemplateOutlet]="entete().template" [ngTemplateOutletContext]="{ $implicit: ligne }" />
              </th>
              @for (cellule of ligne.cases; track $index; let indexColonne = $index) {
                <td
                  [class]="'planning-grille-case ' + cellule.classe"
                  [class.planning-grille-semaine]="jours()[indexColonne]?.debutSemaine"
                  [class.planning-grille-case-active]="cellule.active"
                  [attr.data-ligne]="indexLigne"
                  [attr.data-colonne]="indexColonne"
                  [attr.data-siege-cle]="cellule.active ? 'grille|' + ligne.id + '|' + jours()[indexColonne]?.key : null"
                  [tabindex]="isCurrent(indexLigne, indexColonne) ? 0 : -1"
                  [attr.aria-label]="cellule.libelle"
                  [title]="cellule.libelle"
                  (focus)="courante.set({ ligne: indexLigne, colonne: indexColonne })"
                  (keydown)="naviguer($event, indexLigne, indexColonne)"
                  (click)="activer(ligne, indexColonne)"
                >
                  <ng-container [ngTemplateOutlet]="contenu().template"
                                [ngTemplateOutletContext]="{ $implicit: ligne, index: indexColonne }" />
                </td>
              }
              @for (colonne of syntheses(); track colonne.key) {
                @let valeur = ligne.synthese[colonne.key];
                <td [class]="'planning-grille-synthese ' + (valeur?.classe ?? '')" [title]="valeur?.titre ?? ''">
                  {{ valeur?.texte ?? '' }}
                </td>
              }
            </tr>
          }
        </tbody>
        @if (pied(); as pied) {
          <tfoot>
            <tr>
              <th scope="row" class="planning-grille-entete">{{ pied.libelle }}</th>
              @for (texte of pied.cases; track $index) {
                <td class="planning-grille-pied" [class.planning-grille-semaine]="jours()[$index]?.debutSemaine">{{ texte }}</td>
              }
              @for (colonne of syntheses(); track colonne.key) {
                <td class="planning-grille-synthese planning-grille-pied">{{ pied.synthese[colonne.key] ?? '' }}</td>
              }
            </tr>
          </tfoot>
        }
      </table>
    </div>
  `,
  styleUrl: './planning-grille.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanningGrille<T extends LigneGrille> {
  private readonly hote = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);

  /** What a screen reader hears of the table before its first row. */
  readonly caption = input.required<string>();
  /** The first column's title: « Stand », « Animateur ». */
  readonly header = input.required<string>();
  readonly jours = input.required<readonly JourGrille[]>();
  readonly lignes = input.required<readonly T[]>();
  readonly syntheses = input<readonly ColonneSynthese[]>([]);
  readonly pied = input<PiedGrille | null>(null);
  /** The day the page is on: its column is marked, scrolled into view and holds the tab stop. */
  readonly jourMarque = input<string | null>(null);
  /** Whether a header sorts the lines; the lines themselves are sorted by the caller. */
  readonly triable = input(false);
  readonly tri = model<SortState>(NO_SORT);
  /** A cell holding a seat was opened. */
  readonly caseActivee = output<CaseActivee<T>>();

  protected readonly entete = contentChild.required(GrilleEntete);
  protected readonly contenu = contentChild.required(GrilleCase);
  protected readonly triNom = TRI_NOM;

  /** The cell holding the grid's one tab stop. */
  protected readonly courante = signal({ ligne: 0, colonne: 0 });

  /**
   * The same position, clamped against the lines on screen: a filter that
   * drops the line it pointed at must not take the grid out of the tab order.
   */
  private readonly positionCourante = computed(() => {
    const lignes = this.lignes();
    if (lignes.length === 0) {
      return { ligne: 0, colonne: 0 };
    }
    const { ligne, colonne } = this.courante();
    const ligneTenue = Math.min(Math.max(ligne, 0), lignes.length - 1);
    const last = Math.max((lignes[ligneTenue]?.cases.length ?? 1) - 1, 0);
    return { ligne: ligneTenue, colonne: Math.min(Math.max(colonne, 0), last) };
  });

  constructor() {
    // The day the page moves to becomes the column the tab stop sits in, and
    // comes into view — the grid of a month-long edition scrolls sideways.
    effect(() => {
      const marque = this.jourMarque();
      const colonne = this.jours().findIndex((jour) => jour.key === marque);
      if (colonne < 0) {
        return;
      }
      untracked(() => this.courante.update((courante) => ({ ...courante, colonne })));
      afterNextRender(
        () =>
          this.hote.nativeElement
            .querySelector<HTMLElement>('.planning-grille-marque')
            ?.scrollIntoView?.({ block: 'nearest', inline: 'center' }),
        { injector: this.injector },
      );
    });
  }

  protected isCurrent(ligne: number, colonne: number): boolean {
    const courante = this.positionCourante();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  protected activer(ligne: T, colonne: number): void {
    const jour = this.jours()[colonne];
    if (jour && ligne.cases[colonne]?.active) {
      this.caseActivee.emit({ ligne, jour });
    }
  }

  protected naviguer(event: KeyboardEvent, ligne: number, colonne: number): void {
    const lignes = this.lignes();
    const lastRow = lignes.length - 1;
    const lastColumn = (lignes[ligne]?.cases.length ?? 1) - 1;
    let target: { ligne: number; colonne: number };
    switch (event.key) {
      case 'ArrowRight':
        target = { ligne, colonne: Math.min(colonne + 1, lastColumn) };
        break;
      case 'ArrowLeft':
        target = { ligne, colonne: Math.max(colonne - 1, 0) };
        break;
      case 'ArrowDown':
        target = { ligne: Math.min(ligne + 1, lastRow), colonne };
        break;
      case 'ArrowUp':
        target = { ligne: Math.max(ligne - 1, 0), colonne };
        break;
      case 'Home':
        target = { ligne, colonne: 0 };
        break;
      case 'End':
        target = { ligne, colonne: lastColumn };
        break;
      case 'Enter':
      case ' ':
        event.preventDefault();
        this.activer(lignes[ligne], colonne);
        return;
      default:
        return;
    }
    event.preventDefault();
    this.courante.set(target);
    this.hote.nativeElement
      .querySelector<HTMLElement>(
        `[data-ligne="${target.ligne}"][data-colonne="${target.colonne}"]`,
      )
      ?.focus();
  }

  /** Ascending, then descending, then the source order again. */
  protected sortBy(colonne: string): void {
    const { active, direction } = this.tri();
    if (active !== colonne) {
      this.tri.set({ active: colonne, direction: 'asc' });
    } else if (direction === 'asc') {
      this.tri.set({ active: colonne, direction: 'desc' });
    } else {
      this.tri.set(NO_SORT);
    }
  }

  protected ariaSort(colonne: string): 'ascending' | 'descending' | null {
    const { active, direction } = this.tri();
    if (!this.triable() || active !== colonne || !direction) {
      return null;
    }
    return direction === 'asc' ? 'ascending' : 'descending';
  }

  protected fleche(colonne: string): string {
    const sens = this.ariaSort(colonne);
    if (sens === null) {
      return '';
    }
    return sens === 'ascending' ? ' ↑' : ' ↓';
  }
}
