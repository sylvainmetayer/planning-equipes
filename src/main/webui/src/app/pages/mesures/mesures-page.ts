import { ChangeDetectionStrategy, Component, ViewEncapsulation } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ongletHote } from '../../core/onglet-hote';
import { RealisePage } from '../realise/realise-page';
import { IndicateursView } from './indicateurs-view';

/** The two views of « Mesures d'édition » (`?vue=`): the realised, or the indicators of the solves. */
export type MesuresView = 'realise' | 'indicateurs';

/** `?vue=indicateurs` for the indicators; anything else is the realised, the default. */
export function readMesuresView(value: string | null): MesuresView {
  return value === 'indicateurs' ? 'indicateurs' : 'realise';
}

/**
 * « Mesures d'édition » (issue #272): the measures an edition leaves behind on
 * one screen — the realised against the published plan, and the indicators of
 * its solves, compared across editions. Both live in the one `mesure_edition`
 * table since 4.0; each view is a screen that already existed, and this page
 * only hosts them and keeps the choice in the address (`?vue=`).
 */
@Component({
  selector: 'app-mesures-page',
  imports: [IndicateursView, MatButtonToggleModule, MatIconModule, RealisePage],
  templateUrl: './mesures-page.html',
  // Global by design (AGENTS.md): no stylesheet of its own.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MesuresPage {
  protected readonly views = ongletHote<MesuresView>(
    readMesuresView,
    'realise',
    () => Promise.resolve(true),
    'vue',
  );
}
