import { ChangeDetectionStrategy, Component, computed, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { RouterLink } from '@angular/router';
import { buildHelpSections, filterHelpSections } from './aide-content';

/**
 * In-app user guide: what each screen is for, how the solver is configured,
 * how to read a score and what to change when a planning is not feasible.
 *
 * Read-only and offline by design — it holds no service, makes no HTTP call
 * and never triggers a solve, so it stays usable while a resolution runs and
 * while the reference data is still empty (which is exactly when a new user
 * needs it).
 */
@Component({
  selector: 'app-aide-page',
  imports: [
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule
  ],
  templateUrl: './aide-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class AidePage {
  private readonly sections = buildHelpSections();
  protected readonly query = signal('');
  protected readonly visibleSections = computed(() => filterHelpSections(this.sections, this.query()));
  protected readonly noResult = computed(() => this.visibleSections().length === 0);

  /**
   * Scrolls to a section of the guide. Deliberately programmatic rather than
   * an `href="#id"` anchor: `index.html` declares `<base href="/">`, so a bare
   * fragment resolves against the base URL and not against the current one —
   * clicking the summary navigated to `/#id`, i.e. straight back to the home
   * page. Scrolling by hand also works inside `mat-sidenav-content`, which is
   * the element that actually scrolls here, not the document.
   */
  protected scrollToSection(id: string): void {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
