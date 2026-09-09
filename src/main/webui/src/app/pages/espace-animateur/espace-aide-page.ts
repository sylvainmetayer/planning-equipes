import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { EspaceAideCible, buildEspaceAideSections } from './espace-aide-content';

/**
 * The animateur's own user guide: what this espace lets them do, in the
 * business words of the screens next door — créneau, stand, coéquipiers,
 * foire au planning, organisation. It answers "what can I do here", never
 * "how is the planning computed": solving, scores and découpage belong to the
 * organisers' guide (`pages/aide`), which an animateur cannot even reach.
 *
 * Read-only and offline by design — it holds no state, makes no HTTP call, so
 * it stays usable while the planning has not been published yet, which is
 * exactly when a new animateur has questions. It reads the access token only
 * to link back to the right tab.
 *
 * An accordion rather than a wall of prose: this page is read on a phone,
 * between two stands, to answer one precise question — collapsed headers make
 * that question findable without scrolling through the other nine answers.
 */
@Component({
  selector: 'app-espace-aide-page',
  imports: [RouterLink, MatButtonModule, MatCardModule, MatExpansionModule, MatIconModule],
  templateUrl: './espace-aide-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EspaceAidePage {
  private readonly espace = inject(EspaceAnimateurService);

  protected readonly sections = buildEspaceAideSections();
  protected readonly jeton = this.espace.jeton.asReadonly();

  /**
   * Drives every panel at once. One-way binding, so opening or closing a
   * single panel by hand still works and is not undone until the next « tout
   * déplier ». Deploying everything also makes the browser's own
   * find-in-page useful here, since it only sees expanded panels.
   */
  protected readonly toutDeplie = signal(false);

  protected basculerTout(): void {
    this.toutDeplie.update((deplie) => !deplie);
  }

  /** Route of the tab a section acts on, or `null` while the token is unknown. */
  protected linkTo(target: EspaceAideCible): unknown[] | null {
    const jeton = this.jeton();
    if (!jeton) {
      return null;
    }
    return target === 'planning' ? ['/animateur', jeton] : ['/animateur', jeton, target];
  }

  protected targetLabel(target: EspaceAideCible): string {
    switch (target) {
      case 'echanges':
        return $localize`:@@espace.nav.echanges:Mes échanges`;
      case 'disponibilites':
        return $localize`:@@espace.nav.disponibilites:Mes disponibilités`;
      default:
        return $localize`:@@espace.nav.planning:Mon planning`;
    }
  }
}
