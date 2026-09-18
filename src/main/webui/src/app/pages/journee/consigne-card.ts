import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { ConsignesStore } from '../../core/consignes.store';
import { bandeLabel } from '../consignes/consignes';

/**
 * The consigne of the day on screen (issue #4), next to the relecture panel:
 * the band and the motif when the day is under one, and the link to the
 * Consignes page either way — modifying or lifting on one side, laying one
 * on the other. Nothing is written here; the gesture lives on its own page.
 */
@Component({
  selector: 'app-consigne-card',
  imports: [MatButtonModule, MatCardModule, MatIconModule, RouterLink],
  template: `
    <mat-card appearance="outlined" class="page-card journee-consigne">
      <mat-card-header>
        <h2 mat-card-title i18n="@@journee.consigne.title">Consigne</h2>
      </mat-card-header>
      <mat-card-content>
        @if (consigne(); as consigne) {
          <p class="journee-consigne-etat">
            <mat-icon>gavel</mat-icon>
            <span i18n="@@journee.consigne.sousConsigne"
              >Journée sous consigne : tous les stands fermés de {{ bande() }} — {{ consigne.motif }}</span
            >
          </p>
        } @else {
          <p class="journee-consigne-note" i18n="@@journee.consigne.aucune">
            Aucune consigne sur cette journée : les stands ouvrent selon leurs horaires.
          </p>
        }
      </mat-card-content>
      <mat-card-actions class="card-actions">
        @if (consigne()) {
          <a matButton="tonal" routerLink="/consignes" [queryParams]="{ date: jour() }">
            <mat-icon>edit</mat-icon>
            <ng-container i18n="@@journee.consigne.modifier">Modifier ou lever</ng-container>
          </a>
        } @else {
          <a matButton routerLink="/consignes" [queryParams]="{ date: jour(), nouvelle: '1' }">
            <mat-icon>gavel</mat-icon>
            <ng-container i18n="@@journee.consigne.poser">Poser une consigne sur cette journée</ng-container>
          </a>
        }
      </mat-card-actions>
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ConsigneCard {
  protected readonly store = inject(ConsignesStore);

  /** The day on screen, `AAAA-MM-JJ`. */
  readonly jour = input.required<string>();

  protected readonly consigne = computed(() => this.store.consigneOf(this.jour()));
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
