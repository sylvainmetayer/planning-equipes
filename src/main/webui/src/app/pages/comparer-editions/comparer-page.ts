import { ChangeDetectionStrategy, Component, ViewEncapsulation } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatIconModule } from '@angular/material/icon';
import { ongletHote } from '../../core/onglet-hote';
import { ComparerEditionsPage } from './comparer-editions-page';
import { ComparerVersionsView } from './comparer-versions-view';

/** The two views of « Comparer deux versions » (`?vue=`): the plans, or the editions. */
export type ComparerView = 'versions' | 'editions';

/** `?vue=editions` for the editions; anything else is the plans, the default. */
export function readComparerView(value: string | null): ComparerView {
  return value === 'editions' ? 'editions' : 'versions';
}

/**
 * « Comparer deux versions » (issue #272): one screen for the one question
 * « what changed between these two? », asked of two plan versions or of two
 * editions' reference data. Each view is a screen that already existed; this
 * page only hosts them and keeps the choice in the address (`?vue=`).
 */
@Component({
  selector: 'app-comparer-page',
  imports: [ComparerEditionsPage, ComparerVersionsView, MatButtonToggleModule, MatIconModule],
  templateUrl: './comparer-page.html',
  // Global by design (AGENTS.md): no stylesheet of its own.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ComparerPage {
  protected readonly views = ongletHote<ComparerView>(
    readComparerView,
    'versions',
    () => Promise.resolve(true),
    'vue',
  );
}
