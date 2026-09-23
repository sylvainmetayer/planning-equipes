import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterLink } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { BRANDING } from '../../core/branding';
import { EtatAccessibilite, MentionsLegales } from '../../core/models';
import { BrandLogo } from '../../shared/brand-logo';
import { NewWindowLink } from '../../shared/new-window-link';
import { StatusMessage } from '../../shared/status-message';

/**
 * The accessibility statement (article 47 of loi n° 2005-102). Public and
 * outside both shells, like the three legal pages beside it.
 *
 * <p>The obligation lies on the organisation that deploys this application,
 * not on the repository: only it knows how far its instance was audited, when,
 * and where a barrier is to be reported. The page therefore renders what the
 * deployment configured (`planning.legal.accessibilite.*`) and says plainly
 * what it did not — a statement claiming a compliance nobody measured would be
 * worse than one admitting it is empty.</p>
 */
@Component({
  selector: 'app-declaration-accessibilite-page',
  imports: [
    BrandLogo,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatToolbarModule,
    NewWindowLink,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './declaration-accessibilite-page.html',
  styleUrl: '../../../styles/mentions-legales.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class DeclarationAccessibilitePage {
  protected readonly mentions = signal<MentionsLegales | null>(null);
  protected readonly erreur = signal('');
  protected readonly produit = inject(BRANDING).productName;

  /** Absent when the page was opened in a new tab. */
  protected readonly peutRevenir = signal(typeof history !== 'undefined' && history.length > 1);

  /** The sentence of the RGAA statement for the configured state, empty when none is. */
  protected readonly phraseEtat = computed(() =>
    phraseConformite(this.mentions()?.accessibilite.etat ?? '', this.produit),
  );

  private readonly adminApi = inject(AdminApi);

  constructor() {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.mentions.set(await this.adminApi.legalNotice());
    } catch {
      this.erreur.set(
        $localize`:@@mentions.chargementImpossible:Ces informations n'ont pas pu être chargées. Réessayez dans un instant ; si cela persiste, prévenez l'organisation.`,
      );
    }
  }

  protected revenir(): void {
    history.back();
  }
}

/** The wording the RGAA model statement uses for each state. */
export function phraseConformite(etat: EtatAccessibilite, produit: string): string {
  switch (etat) {
    case 'totale':
      return $localize`:@@accessibilite.etat.totale:${produit}:produit: est en conformité totale avec le référentiel général d'amélioration de l'accessibilité (RGAA), version 4.1.`;
    case 'partielle':
      return $localize`:@@accessibilite.etat.partielle:${produit}:produit: est en conformité partielle avec le référentiel général d'amélioration de l'accessibilité (RGAA), version 4.1.`;
    case 'non':
      return $localize`:@@accessibilite.etat.non:${produit}:produit: n'est pas en conformité avec le référentiel général d'amélioration de l'accessibilité (RGAA), version 4.1.`;
    default:
      return '';
  }
}
