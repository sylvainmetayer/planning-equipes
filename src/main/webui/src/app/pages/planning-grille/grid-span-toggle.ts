import { ChangeDetectionStrategy, Component, model } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { GridSpan } from './jours-grille';

/** The span switch both grids carry in their bar: the day, its week, the whole event. */
@Component({
  selector: 'app-grid-span-toggle',
  imports: [MatButtonToggleModule],
  template: `
    <mat-button-toggle-group [value]="span()" (change)="span.set($event.value)"
                             aria-label="Journées affichées" i18n-aria-label="@@planningGrille.portee.label">
      <mat-button-toggle value="jour" i18n="@@planningGrille.portee.jour">Ce jour</mat-button-toggle>
      <mat-button-toggle value="semaine" i18n="@@planningGrille.portee.semaine">Sa semaine</mat-button-toggle>
      <mat-button-toggle value="evenement" i18n="@@planningGrille.portee.evenement">Tout l'événement</mat-button-toggle>
    </mat-button-toggle-group>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class GridSpanToggle {
  readonly span = model<GridSpan>('semaine');
}
