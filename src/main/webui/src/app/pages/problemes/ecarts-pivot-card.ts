// « Où se concentrent les écarts » on the Diagnostic's Problèmes tab: the
// pivot that lived, folded, on the rules screen, now open under the
// cards and narrowed to the rule a card asked about. A cell leads to the one
// screen where its breaches are fixed — a day or a stand on the Planning page,
// a person's fiche.

import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  model,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { Router } from '@angular/router';
import { nextGridCell } from '../../core/grid-navigation';
import { AxePivot, ConstraintsView } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { compareCodeUnits } from '../../core/string-order';
import { currentViewParams, keepViewInQueryParams } from '../../core/view-query-params';
import { buildPivot, cellLink, classeCellule, ColonnePivot } from './ecarts-pivot';

/** The three readings of « où se concentrent les écarts ». */
const AXES: readonly AxePivot[] = ['JOUR', 'STAND', 'ANIMATEUR'];

/** Called lazily: never at module scope. */
function axeLabel(axe: AxePivot): string {
  switch (axe) {
    case 'JOUR':
      return $localize`:@@constraints.pivot.axe.jour:Par journée`;
    case 'STAND':
      return $localize`:@@constraints.pivot.axe.stand:Par stand`;
    case 'ANIMATEUR':
      return $localize`:@@constraints.pivot.axe.animateur:Par animateur`;
  }
}

function readAxe(value: string | null): AxePivot {
  return AXES.find((axe) => axe === value) ?? 'JOUR';
}

@Component({
  selector: 'app-ecarts-pivot-card',
  imports: [MatButtonModule, MatCardModule, MatChipsModule],
  templateUrl: './ecarts-pivot-card.html',
  styleUrls: ['../../../styles/heatmap.css', './ecarts-pivot-card.css'],
  // Global by design (AGENTS.md): loaded with the route that shows the tab.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'ecarts-pivot' },
})
export class EcartsPivotCard {
  /** The diagnostic of the last analysis: its pivot cells, and its rules for their short labels. */
  readonly view = input<ConstraintsView | null>(null);
  /** The rule the table is narrowed to; empty for every rule in default. */
  readonly regle = model('');

  private readonly referentiel = inject(ReferenceDataStore);
  private readonly router = inject(Router);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly axes = AXES.map((axe) => ({ value: axe, label: axeLabel(axe) }));
  protected readonly axe = signal<AxePivot>(readAxe(currentViewParams().get('axe')));

  /** Rule name → its short label: the rows read as the cards do, never in camelCase. */
  private readonly labels = computed(
    () =>
      new Map(
        (this.view()?.contraintes ?? []).map((contrainte) => [
          contrainte.name,
          contrainte.libelleCourt || contrainte.name,
        ]),
      ),
  );

  /** The label of the rule the table is narrowed to, empty when it shows them all. */
  protected readonly regleLabel = computed(() => {
    const regle = this.regle();
    return regle ? (this.labels().get(regle) ?? regle) : '';
  });

  protected readonly pivot = computed(() => {
    const axe = this.axe();
    const regle = this.regle();
    const cellules = (this.view()?.pivotEcarts ?? []).filter(
      (cellule) => !regle || cellule.contrainte === regle,
    );
    return buildPivot(
      cellules,
      axe,
      (cle) => this.keyLabel(axe, cle),
      axe === 'JOUR'
        ? (a: ColonnePivot, b: ColonnePivot) => compareCodeUnits(a.cle, b.cle)
        : undefined,
    );
  });

  /** The cell holding the focus (roving tabindex), clamped to the table on screen. */
  protected readonly focusedCell = signal({ ligne: 0, colonne: 0 });
  private readonly focusedPosition = computed(() => {
    const { lignes, colonnes } = this.pivot();
    const { ligne, colonne } = this.focusedCell();
    return {
      ligne: Math.min(Math.max(ligne, 0), Math.max(lignes.length - 1, 0)),
      colonne: Math.min(Math.max(colonne, 0), Math.max(colonnes.length - 1, 0)),
    };
  });

  constructor() {
    keepViewInQueryParams(() => ({ axe: this.axe() === 'JOUR' ? null : this.axe() }));
  }

  protected setAxe(axe: AxePivot): void {
    this.axe.set(axe);
  }

  protected showAll(): void {
    this.regle.set('');
  }

  protected ruleLabel(contrainte: string): string {
    return this.labels().get(contrainte) ?? contrainte;
  }

  protected classeCellule(ecarts: number, maximum: number): string {
    return classeCellule(ecarts, maximum);
  }

  /** « 4 » alone says neither which rule nor where: the cell names both. */
  protected cellLabel(contrainte: string, colonne: ColonnePivot, ecarts: number): string {
    const regle = this.ruleLabel(contrainte);
    return $localize`:@@constraints.pivot.cellLabel:${regle}:contrainte: — ${colonne.libelle}:colonne: : ${ecarts}:ecarts: écart(s)`;
  }

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  /** A cell with breaches opens the screen where they are fixed; an empty one leads nowhere. */
  protected openCell(colonne: ColonnePivot | undefined, ecarts: number): void {
    if (!colonne || ecarts === 0) {
      return;
    }
    const lien = cellLink(this.axe(), colonne.cle, colonne.libelle);
    void this.router.navigate([lien.route], { queryParams: lien.queryParams });
  }

  protected onCellKeydown(event: KeyboardEvent, ligne: number, colonne: number): void {
    const { lignes, colonnes } = this.pivot();
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      this.openCell(colonnes[colonne], lignes[ligne]?.ecarts[colonne] ?? 0);
      return;
    }
    const target = nextGridCell(
      event.key,
      { ligne, colonne },
      lignes.length - 1,
      colonnes.length - 1,
    );
    if (!target) {
      return;
    }
    event.preventDefault();
    this.focusedCell.set(target);
    this.host.nativeElement
      .querySelector<HTMLElement>(
        `[data-ligne="${target.ligne}"][data-colonne="${target.colonne}"]`,
      )
      ?.focus();
  }

  /** What the reader sees in a column header: a date, a stand's name, a full name. */
  private keyLabel(axe: AxePivot, cle: string): string {
    if (axe === 'STAND') {
      return this.referentiel.stands().find((stand) => stand.id === cle)?.nom || cle;
    }
    if (axe === 'ANIMATEUR') {
      const animateur = this.referentiel.animateurs().find((candidate) => candidate.id === cle);
      return animateur ? `${animateur.prenom} ${animateur.nom}`.trim() : cle;
    }
    return cle;
  }
}
