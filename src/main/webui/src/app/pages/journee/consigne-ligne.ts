import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { bandeLabel } from '../../core/consigne-wording';
import { ConsignesStore } from '../../core/consignes.store';

/**
 * The consigne of the day on screen (issue #4), in one line next to the
 * relecture chips (issue #712): the band and the motif when the day is under
 * one, with the link that modifies or lifts it; otherwise the link that lays
 * one — on a day still to come only: a consigne is laid on days ahead, and a
 * link the server would refuse is not offered. Nothing is written here; the
 * gesture lives on its own page.
 */
@Component({
  selector: 'app-consigne-ligne',
  imports: [MatIconModule, RouterLink],
  template: `
    <p class="journee-consigne">
      <mat-icon inline aria-hidden="true">gavel</mat-icon>
      @if (consigne(); as consigne) {
        <span class="journee-consigne-etat" i18n="@@journee.consigne.sousConsigne"
          >Journée sous consigne : tous les stands fermés de {{ bande() }} — {{ consigne.motif }}</span
        >
        <a routerLink="/consignes" [queryParams]="{ date: jour() }" i18n="@@journee.consigne.modifier">Modifier ou lever</a>
      } @else if (peutPoser()) {
        <a routerLink="/consignes" [queryParams]="{ date: jour(), nouvelle: '1' }"
           i18n="@@journee.consigne.poser">Poser une consigne sur cette journée</a>
      } @else {
        <a routerLink="/consignes" i18n="@@journee.consigne.aucuneVoir">Aucune consigne</a>
      }
    </p>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsigneLigne {
  protected readonly store = inject(ConsignesStore);

  /** The day on screen, `AAAA-MM-JJ`. */
  readonly jour = input.required<string>();

  protected readonly consigne = computed(() => this.store.consigneOf(this.jour()));
  /** A consigne is laid on a day strictly after the server's today — unknown before the first read. */
  protected readonly peutPoser = computed(() => {
    const aujourdhui = this.store.aujourdhui();
    return aujourdhui !== null && this.jour() > aujourdhui;
  });
  protected readonly bande = computed(() => {
    const consigne = this.consigne();
    return consigne ? bandeLabel(consigne.fermetureDebut, consigne.fermetureFin) : '';
  });

  constructor() {
    if (!this.store.etat()) {
      void this.store.reload();
    }
  }
}
