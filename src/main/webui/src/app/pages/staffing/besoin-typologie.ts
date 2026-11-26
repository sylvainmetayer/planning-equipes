import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  ViewEncapsulation,
} from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { intlLocale } from '../../core/locale';
import { LigneTypologie } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { StatusMessage } from '../../shared/status-message';

/**
 * « Le planning enregistré, typologie par typologie »: the persisted plan read by
 * game category, one table at the foot of Besoin — seats, hours, who is vetted
 * on it, who held it without being. The reading « Par typologie » was on the
 * Planning page; it is a reading of the need, so it lives with it. Every figure
 * is a link to where it is acted on: the seats and the hours to the Planning
 * by stand narrowed to the category, the vetted to the Animateurs list filtered
 * on it, the ones seated without the skill to the Compétences grid.
 */
@Component({
  selector: 'app-besoin-typologie',
  imports: [MatProgressBarModule, RouterLink, StatusMessage],
  template: `
    @if (lecture.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <app-status-message [text]="erreur()" tone="error" />
    @if (lecture.hasValue()) {
      @if (lignes().length === 0) {
        <p class="empty-hint" i18n="@@besoinTypologie.aucune">Aucune typologie dans cette édition.</p>
      } @else {
        <div class="table-wrapper">
          <table class="besoin-typologie-table">
            <caption class="visually-hidden" i18n="@@besoinTypologie.caption">
              Le planning enregistré par typologie de jeu : postes, heures, compétents, et affectés sans la compétence
            </caption>
            <thead>
              <tr>
                <th scope="col" i18n="@@besoinTypologie.col.typologie">Typologie</th>
                <th scope="col" class="besoin-typologie-nombre" i18n="@@besoinTypologie.col.postes">Postes</th>
                <th scope="col" class="besoin-typologie-nombre" i18n="@@besoinTypologie.col.heures">Heures</th>
                <th scope="col" class="besoin-typologie-nombre" i18n="@@besoinTypologie.col.plafond">Plafond par animateur</th>
                <th scope="col" class="besoin-typologie-nombre" i18n="@@besoinTypologie.col.affectes">Affectés</th>
                <th scope="col" class="besoin-typologie-nombre" i18n="@@besoinTypologie.col.competents">Compétents</th>
                <th scope="col" class="besoin-typologie-nombre" i18n="@@besoinTypologie.col.jamais">Compétents jamais affectés</th>
                <th scope="col" class="besoin-typologie-nombre" i18n="@@besoinTypologie.col.sansCompetence">Affectés sans la compétence</th>
              </tr>
            </thead>
            <tbody>
              @for (ligne of lignes(); track ligne.typologie) {
                <tr>
                  <th scope="row">
                    {{ ligne.label }}
                    @if (ligne.polyvalent) {
                      <span class="besoin-typologie-polyvalent" i18n="@@besoinTypologie.polyvalent">polyvalente</span>
                    }
                    @if (ligne.description) {
                      <span class="besoin-typologie-description">{{ ligne.description }}</span>
                    }
                  </th>
                  <td class="besoin-typologie-nombre">
                    <a routerLink="/journee" [queryParams]="{ vue: 'stand', typologie: ligne.typologie }"
                       [title]="byStandLabel">{{ ligne.postes }}</a>
                  </td>
                  <td class="besoin-typologie-nombre">
                    <a routerLink="/journee" [queryParams]="{ vue: 'stand', typologie: ligne.typologie }"
                       [title]="byStandLabel">{{ heures(ligne.heures) }}</a>
                  </td>
                  <td class="besoin-typologie-nombre">{{ ligne.maxCreneauxParAnimateur ?? '—' }}</td>
                  <td class="besoin-typologie-nombre">{{ ligne.animateursAffectes.length }}</td>
                  <td class="besoin-typologie-nombre">
                    <a routerLink="/animateurs" [queryParams]="{ typologie: ligne.typologie }"
                       [title]="animateursLabel">{{ ligne.animateursCompetents.length }}</a>
                  </td>
                  <td class="besoin-typologie-nombre">
                    <a routerLink="/animateurs" [queryParams]="{ typologie: ligne.typologie }"
                       [title]="animateursLabel">{{ ligne.competentsJamaisAffectes.length }}</a>
                    @if (ligne.competentsJamaisAffectes.length > 0) {
                      <span class="besoin-typologie-noms">
                        @for (animateur of ligne.competentsJamaisAffectes; track animateur.animateurId) {
                          <a [routerLink]="['/animateurs', animateur.animateurId]" [title]="ficheLabel">{{ animateur.nom }}</a>
                        }
                      </span>
                    }
                  </td>
                  <td class="besoin-typologie-nombre"
                      [class.besoin-typologie-alerte]="ligne.affectesSansCompetence.length > 0">
                    <a routerLink="/animateurs" [queryParams]="{ onglet: 'competences', typologies: ligne.typologie }"
                       [title]="competencesLabel">{{ ligne.affectesSansCompetence.length }}</a>
                    @if (ligne.affectesSansCompetence.length > 0) {
                      <span class="besoin-typologie-noms">
                        @for (animateur of ligne.affectesSansCompetence; track animateur.animateurId) {
                          <a [routerLink]="['/animateurs', animateur.animateurId]" [title]="ficheLabel">{{ animateur.nom }}</a>
                        }
                      </span>
                    }
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
      }
    }
  `,
  styleUrl: './besoin-typologie.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BesoinTypologie {
  private readonly planningApi = inject(PlanningApi);

  protected readonly lecture = resource({ loader: () => this.planningApi.typologiesReport() });
  /** True while the report is read, so a host can wait before scrolling past this table. */
  readonly loading = computed(() => this.lecture.isLoading());
  protected readonly erreur = errorText(this.lecture);

  protected readonly lignes = computed<LigneTypologie[]>(() =>
    this.lecture.hasValue() ? this.lecture.value().typologies : [],
  );

  protected readonly byStandLabel = $localize`:@@besoinTypologie.lien.parStand:Voir ces stands, jour par jour`;
  protected readonly animateursLabel = $localize`:@@besoinTypologie.lien.animateurs:Voir les animateurs compétents sur cette typologie`;
  protected readonly competencesLabel = $localize`:@@besoinTypologie.lien.competences:Ouvrir la grille des compétences sur cette typologie`;
  protected readonly ficheLabel = $localize`:@@planningPersonne.fiche:Ouvrir la fiche de la personne`;

  protected heures(valeur: number): string {
    return valeur.toLocaleString(intlLocale(), { maximumFractionDigits: 1 });
  }
}
