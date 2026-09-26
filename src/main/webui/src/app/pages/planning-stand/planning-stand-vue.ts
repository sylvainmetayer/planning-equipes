import {
  ChangeDetectionStrategy,
  Component,
  computed,
  input,
  model,
  output,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { RouterLink } from '@angular/router';
import { PlanningEvenement } from '../../core/models';
import { JourEvenement } from '../journee/journee';
import { joursGrille } from '../planning-grille/jours-grille';
import {
  CaseActivee,
  GrilleCase,
  GrilleEntete,
  LigneGrille,
  PlanningGrille,
} from '../planning-grille/planning-grille';
import {
  buildTableauStands,
  CaseStand,
  colonnesStand,
  DensiteStand,
  LigneStand,
} from './planning-stand';

/**
 * « Par stand » (issue #713): one line per stand, one column per day, the
 * names in the cells — the sheet an organiser coming from a spreadsheet keeps
 * — with what a spreadsheet does not compute: a cell coloured by its
 * coverage, closed told apart from empty, the seats to fill and filled, the
 * share of seat-hours held and the hours at the right, the day's totals at
 * the foot. It replaces the stand mode of the Heatmap, and the table the
 * Répartition des heures kept folded.
 *
 * <p>A cell opens the Siège panel of the page on that stand and that day —
 * its first empty seat, else its first one. The stand's name leads to its
 * fiche. Read from the plan the page already holds: nothing is fetched.</p>
 */
@Component({
  selector: 'app-planning-stand-vue',
  imports: [MatButtonToggleModule, RouterLink, PlanningGrille, GrilleEntete, GrilleCase],
  template: `
    <div class="planning-axe-barre">
      <mat-button-toggle-group [value]="densite()" (change)="densite.set($event.value)"
                               aria-label="Contenu des cases" i18n-aria-label="@@planningStand.densite.label">
        <mat-button-toggle value="noms" i18n="@@planningStand.densite.noms">Noms</mat-button-toggle>
        <mat-button-toggle value="compteurs" i18n="@@planningStand.densite.compteurs">Compteurs</mat-button-toggle>
        <mat-button-toggle value="couverture" i18n="@@planningStand.densite.couverture">Couverture</mat-button-toggle>
      </mat-button-toggle-group>
      <ul class="planning-grille-legende">
        <li><span class="planning-grille-pastille planning-stand-pourvu"></span><ng-container i18n="@@planningStand.legende.pourvu">pourvu</ng-container></li>
        <li><span class="planning-grille-pastille planning-stand-partiel"></span><ng-container i18n="@@planningStand.legende.partiel">partiel</ng-container></li>
        <li><span class="planning-grille-pastille planning-stand-vide"></span><ng-container i18n="@@planningStand.legende.vide">vide</ng-container></li>
        <li><span class="planning-grille-pastille planning-stand-ferme"></span><ng-container i18n="@@planningStand.legende.ferme">fermé</ng-container></li>
      </ul>
    </div>
    @if (tableau().lignes.length === 0) {
      @if (planning()?.postes?.length) {
        <p class="empty-hint" i18n="@@planningStand.aucun">Aucun stand ne correspond aux filtres.</p>
      } @else {
        <p class="empty-hint" i18n="@@calendarDay.empty">Aucune donnée de planning disponible pour le moment. Lancez une résolution depuis la page Solveur.</p>
      }
    } @else {
      <app-planning-grille
        [class]="'planning-stand-densite-' + densite()"
        caption="Par stand : une ligne par stand, une colonne par journée, qui tient ses sièges, puis ce qu'il reste à pourvoir"
        i18n-caption="@@planningStand.caption"
        header="Stand"
        i18n-header="@@planningStand.enTete"
        [jours]="colonnes()"
        [lignes]="tableau().lignes"
        [syntheses]="syntheses"
        [pied]="tableau().pied"
        [jourMarque]="jourMarque()"
        (caseActivee)="open($event)"
      >
        <ng-template appGrilleEntete let-ligne>
          <a class="planning-grille-lien" [routerLink]="['/stands', stand(ligne).standId]"
             [title]="ficheLabel">{{ stand(ligne).standNom }}</a>
          @if (stand(ligne).emplacementNom) {
            <span class="planning-grille-sous-titre">{{ stand(ligne).emplacementNom }}</span>
          }
        </ng-template>
        <ng-template appGrilleCase let-ligne let-index="index">
          @let cellule = cellOf(ligne, index);
          @switch (densite()) {
            @case ('noms') {
              @for (nom of cellule.noms; track $index) {
                <span class="planning-stand-nom">{{ nom }}</span>
              }
              @if (cellule.statut !== 'ferme' && cellule.pourvus < cellule.sieges) {
                <span class="planning-stand-manquants" i18n="@@planningStand.case.manquants">{{ cellule.sieges - cellule.pourvus }} vide(s)</span>
              }
            }
            @case ('compteurs') {
              @if (cellule.statut !== 'ferme') {
                {{ cellule.pourvus }}/{{ cellule.sieges }}
              }
            }
          }
        </ng-template>
      </app-planning-grille>
    }
  `,
  styleUrl: './planning-stand-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanningStandView {
  readonly planning = input<PlanningEvenement | null>(null);
  /** The page's days, in order. */
  readonly jours = input<readonly JourEvenement[]>([]);
  /** The day the page is on, marked in the grid. */
  readonly jourMarque = input<string | null>(null);
  /** The page's filters: the stands its stand, location and game-category filters leave, a person, a text. */
  readonly standsRetenus = input<ReadonlySet<string> | null>(null);
  readonly animateur = input('');
  readonly filtre = input('');
  /** What a cell shows — the view state this rendering owns (`densite`). */
  readonly densite = model<DensiteStand>('noms');
  /** A cell was opened: the page opens the Siège panel on the seat, and moves to its day. */
  readonly seatRequested = output<{ posteId: string; jour: string }>();

  protected readonly syntheses = colonnesStand();
  protected readonly ficheLabel = $localize`:@@calendarDay.ficheStand:Ouvrir la fiche du stand`;

  protected readonly colonnes = computed(() => joursGrille(this.jours()));
  protected readonly tableau = computed(() =>
    buildTableauStands(this.planning()?.postes ?? [], this.jours(), this.colonnes(), {
      standsRetenus: this.standsRetenus(),
      animateur: this.animateur(),
      recherche: this.filtre(),
    }),
  );

  /** The grid's templates are handed its generic line: read back as the line this axis built. */
  protected stand(ligne: LigneGrille): LigneStand {
    return ligne as LigneStand;
  }

  protected cellOf(ligne: LigneGrille, index: number): CaseStand {
    return (ligne as LigneStand).cases[index];
  }

  protected open(event: CaseActivee<LigneStand>): void {
    const posteId = event.ligne.cases[this.colonnes().indexOf(event.jour)]?.posteId;
    if (posteId) {
      this.seatRequested.emit({ posteId, jour: event.jour.key });
    }
  }
}
