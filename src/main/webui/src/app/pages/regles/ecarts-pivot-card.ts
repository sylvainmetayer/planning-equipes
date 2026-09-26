// « Où se concentrent les écarts » (issue #496), as a card of its own: the
// rules in default down, the days, stands or animateurs across, a cell opened
// on what to do about it. It lived inside the constraints screen; lifted out
// whole, with nothing of that page it needs, so the Diagnostic can take it
// over (issue #721) by dropping the element into its « Problèmes » tab.

import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { nextGridCell } from '../../core/grid-navigation';
import { AxePivot, ConstraintsView } from '../../core/models';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { ColonnePivot, buildPivot, cellLink, classeCellule } from './ecarts-pivot';

/** The three readings of « où se concentrent les écarts ». */
const AXES: AxePivot[] = ['JOUR', 'STAND', 'ANIMATEUR'];

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

@Component({
  selector: 'app-ecarts-pivot-card',
  imports: [MatButtonModule, MatCardModule, MatChipsModule, MatIconModule, RouterLink],
  templateUrl: './ecarts-pivot-card.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EcartsPivotCard {
  /** The analysed catalogue: its `pivotEcarts` cells, and each rule's label and lever. */
  readonly view = input<ConstraintsView | null>(null);

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly referentiel = inject(ReferenceDataStore);

  protected readonly axes = AXES.map((axe) => ({ value: axe, label: axeLabel(axe) }));
  protected readonly axe = signal<AxePivot>('JOUR');
  /** The cell the reader opened, or null — its lines are listed under the table. */
  protected readonly openedCell = signal<{ contrainte: string; cle: string } | null>(null);
  /**
   * Folded until asked for: it answers « où », a question one only has once
   * the table of rules has said « combien ».
   */
  protected readonly ouvert = signal(false);

  /** A rule named by its short label, never its technical name. */
  private readonly libelles = computed(
    () =>
      new Map(
        (this.view()?.contraintes ?? []).map((rule) => [rule.name, rule.libelleCourt || rule.name]),
      ),
  );

  protected libelleRegle(name: string): string {
    return this.libelles().get(name) ?? name;
  }

  /**
   * Days read chronologically, which is the only order that answers « est-ce
   * le week-end » ; stands and animateurs read most-breaches-first.
   */
  protected readonly pivot = computed(() => {
    const axe = this.axe();
    return buildPivot(
      this.view()?.pivotEcarts ?? [],
      axe,
      (cle) => this.libellePivot(axe, cle),
      axe === 'JOUR' ? (a: ColonnePivot, b: ColonnePivot) => a.cle.localeCompare(b.cle) : undefined,
    );
  });

  protected classeCellule(ecarts: number, maximum: number): string {
    return classeCellule(ecarts, maximum);
  }

  /** « 4 » alone says neither which rule nor where: the cell names both. */
  protected cellLabel(contrainte: string, colonne: ColonnePivot, ecarts: number): string {
    const regle = this.libelleRegle(contrainte);
    return $localize`:@@constraints.pivot.cellLabel:${regle}:contrainte: — ${colonne.libelle}:colonne: : ${ecarts}:ecarts: écart(s)`;
  }

  /** The cell the table hands the focus to (roving tabindex), clamped to the table on screen. */
  protected readonly focusedCell = signal({ ligne: 0, colonne: 0 });

  private readonly focusedPosition = computed(() => {
    const { lignes, colonnes } = this.pivot();
    const { ligne, colonne } = this.focusedCell();
    return {
      ligne: Math.min(Math.max(ligne, 0), Math.max(lignes.length - 1, 0)),
      colonne: Math.min(Math.max(colonne, 0), Math.max(colonnes.length - 1, 0)),
    };
  });

  protected isFocusedCell(ligne: number, colonne: number): boolean {
    const courante = this.focusedPosition();
    return courante.ligne === ligne && courante.colonne === colonne;
  }

  protected onCellKeydown(event: KeyboardEvent, ligne: number, colonne: number): void {
    const { lignes, colonnes } = this.pivot();
    if (event.key === 'Enter' || event.key === ' ') {
      event.preventDefault();
      const row = lignes[ligne];
      if (row) {
        this.openCell(row.contrainte, colonnes[colonne], row.ecarts[colonne] ?? 0);
      }
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

  protected changerAxe(axe: AxePivot): void {
    this.axe.set(axe);
    this.openedCell.set(null);
  }

  protected openCell(contrainte: string, colonne: ColonnePivot, ecarts: number): void {
    if (ecarts === 0) {
      return;
    }
    const opened = this.openedCell();
    this.openedCell.set(
      opened?.contrainte === contrainte && opened.cle === colonne.cle
        ? null
        : { contrainte, cle: colonne.cle },
    );
  }

  /**
   * The lines behind the opened cell. Only the hard rules carry their lines:
   * the server lists them for those alone, medium and soft ones running into
   * the thousands of matches — a cell of a medium rule opens on a count and a
   * sentence saying why.
   */
  protected readonly detailCellule = computed(() => {
    const opened = this.openedCell();
    if (opened === null) {
      return null;
    }
    const axe = this.axe();
    const contrainte = this.view()?.contraintes.find(
      (candidate) => candidate.name === opened.contrainte,
    );
    const ecarts =
      this.view()?.pivotEcarts.find(
        (cellule) =>
          cellule.axe === axe &&
          cellule.contrainte === opened.contrainte &&
          cellule.cle === opened.cle,
      )?.ecarts ?? 0;
    const lignes = (contrainte?.references ?? [])
      .filter((reference) => this.referenceTouche(axe, reference, opened.cle))
      .map((reference) => reference.texte);
    return {
      contrainte: opened.contrainte,
      libelle: this.libelleRegle(opened.contrainte),
      colonne: this.libellePivot(axe, opened.cle),
      ecarts,
      lignes,
      listable: contrainte?.niveau === 'HARD',
      remediation: contrainte?.remediation ?? '',
      lien: cellLink(axe, opened.cle, this.libellePivot(axe, opened.cle)),
    };
  });

  /** What the reader sees in a column header: a date, a stand's name, a full name. */
  private libellePivot(axe: AxePivot, cle: string): string {
    if (axe === 'STAND') {
      const stand = this.referentiel.stands().find((candidate) => candidate.id === cle);
      return stand?.nom || cle;
    }
    if (axe === 'ANIMATEUR') {
      const animateur = this.referentiel.animateurs().find((candidate) => candidate.id === cle);
      return animateur ? `${animateur.prenom} ${animateur.nom}`.trim() : cle;
    }
    return cle;
  }

  /** Whether a violation line names that key on that axis — the ids the server sends with it. */
  private referenceTouche(
    axe: AxePivot,
    reference: { animateurId: string | null; standId: string | null; creneauId: number | null },
    cle: string,
  ): boolean {
    if (axe === 'ANIMATEUR') {
      return reference.animateurId === cle;
    }
    if (axe === 'STAND') {
      return reference.standId === cle;
    }
    const creneau = this.referentiel
      .creneaux()
      .find((candidate) => candidate.id === reference.creneauId);
    return creneau?.date === cle;
  }
}
