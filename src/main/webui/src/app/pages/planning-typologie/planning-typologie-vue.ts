import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  resource,
  ViewEncapsulation,
} from '@angular/core';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { intlLocale } from '../../core/locale';
import { LigneTypologie } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { correspondAuFiltre } from '../../core/text-filter';
import { StatusMessage } from '../../shared/status-message';

/**
 * « Par typologie » (issue #713): the persisted plan read by game category,
 * in one table — seats, hours, who is vetted on it, who held it without being
 * (the organiser's note on the category under its name)
 * — every figure a link to the screen where it is acted on: the seats and the
 * hours to « Par stand » narrowed to the category, the vetted to the
 * Animateurs list filtered on it, the ones seated without the skill to the
 * Compétences grid on its column. The three other renderings of the former
 * « Planning par typologie » screen (bars, heatmap, cards) are gone: they drew
 * these same figures, and none of them could be clicked.
 */
@Component({
  selector: 'app-planning-typologie-vue',
  imports: [MatProgressBarModule, RouterLink, StatusMessage],
  template: `
    @if (lecture.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    <app-status-message [text]="erreur()" tone="error" />
    @if (lecture.hasValue()) {
      @if (lignes().length === 0) {
        <p class="empty-hint" i18n="@@planningTypologie.aucune">Aucune typologie ne correspond aux filtres.</p>
      } @else {
        <div class="table-wrapper">
          <table class="planning-typologie-table">
            <caption class="visually-hidden" i18n="@@planningTypologie.caption">
              Le planning enregistré par typologie de jeu : postes, heures, compétents, et affectés sans la compétence
            </caption>
            <thead>
              <tr>
                <th scope="col" i18n="@@planningTypologie.col.typologie">Typologie</th>
                <th scope="col" class="planning-typologie-nombre" i18n="@@planningTypologie.col.postes">Postes</th>
                <th scope="col" class="planning-typologie-nombre" i18n="@@planningTypologie.col.heures">Heures</th>
                <th scope="col" class="planning-typologie-nombre" i18n="@@planningTypologie.col.plafond">Plafond par animateur</th>
                <th scope="col" class="planning-typologie-nombre" i18n="@@planningTypologie.col.affectes">Affectés</th>
                <th scope="col" class="planning-typologie-nombre" i18n="@@planningTypologie.col.competents">Compétents</th>
                <th scope="col" class="planning-typologie-nombre" i18n="@@planningTypologie.col.jamais">Compétents jamais affectés</th>
                <th scope="col" class="planning-typologie-nombre" i18n="@@planningTypologie.col.sansCompetence">Affectés sans la compétence</th>
              </tr>
            </thead>
            <tbody>
              @for (ligne of lignes(); track ligne.typologie) {
                <tr>
                  <th scope="row">
                    {{ ligne.label }}
                    @if (ligne.ninja) {
                      <span class="planning-typologie-ninja" i18n="@@planningTypologie.ninja">ninja</span>
                    }
                    @if (ligne.description) {
                      <span class="planning-typologie-description">{{ ligne.description }}</span>
                    }
                  </th>
                  <td class="planning-typologie-nombre">
                    <button type="button" class="planning-typologie-lien" [title]="byStandLabel"
                            (click)="standsDemandes.emit(ligne.typologie)">{{ ligne.postes }}</button>
                  </td>
                  <td class="planning-typologie-nombre">
                    <button type="button" class="planning-typologie-lien" [title]="byStandLabel"
                            (click)="standsDemandes.emit(ligne.typologie)">{{ heures(ligne.heures) }}</button>
                  </td>
                  <td class="planning-typologie-nombre">{{ ligne.maxCreneauxParAnimateur ?? '—' }}</td>
                  <td class="planning-typologie-nombre">{{ ligne.animateursAffectes.length }}</td>
                  <td class="planning-typologie-nombre">
                    <a routerLink="/animateurs" [queryParams]="{ typologie: ligne.typologie }"
                       [title]="animateursLabel">{{ ligne.animateursCompetents.length }}</a>
                  </td>
                  <td class="planning-typologie-nombre">
                    <a routerLink="/animateurs" [queryParams]="{ typologie: ligne.typologie }"
                       [title]="animateursLabel">{{ ligne.competentsJamaisAffectes.length }}</a>
                  </td>
                  <td class="planning-typologie-nombre"
                      [class.planning-typologie-alerte]="ligne.affectesSansCompetence.length > 0">
                    <a routerLink="/competences" [queryParams]="{ typologies: ligne.typologie }"
                       [title]="competencesLabel">{{ ligne.affectesSansCompetence.length }}</a>
                  </td>
                </tr>
              }
            </tbody>
          </table>
        </div>
        <p class="empty-hint" i18n="@@planningTypologie.note">
          Un stand qui propose plusieurs typologies compte un poste dans chacune.
        </p>
      }
    }
  `,
  styleUrl: './planning-typologie-vue.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class PlanningTypologieView {
  private readonly planningApi = inject(PlanningApi);

  /** The page's game-category filter: one typologie id, or every one. */
  readonly typologie = input('');
  /** The page's text filter, over the label and the id. */
  readonly filtre = input('');
  /** The seats or the hours of a category were clicked: « Par stand », narrowed to it. */
  readonly standsDemandes = output<string>();

  protected readonly lecture = resource({ loader: () => this.planningApi.typologiesReport() });
  protected readonly erreur = errorText(this.lecture);

  protected readonly lignes = computed<LigneTypologie[]>(() => {
    const typologies = this.lecture.hasValue() ? this.lecture.value().typologies : [];
    return typologies.filter(
      (ligne) =>
        (!this.typologie() || ligne.typologie === this.typologie()) &&
        correspondAuFiltre(this.filtre(), [ligne.label, ligne.typologie, ligne.description]),
    );
  });

  protected readonly byStandLabel = $localize`:@@planningTypologie.lien.parStand:Voir ces stands, jour par jour`;
  protected readonly animateursLabel = $localize`:@@planningTypologie.lien.animateurs:Voir les animateurs compétents sur cette typologie`;
  protected readonly competencesLabel = $localize`:@@planningTypologie.lien.competences:Ouvrir la grille des compétences sur cette typologie`;

  protected heures(valeur: number): string {
    return valeur.toLocaleString(intlLocale(), { maximumFractionDigits: 1 });
  }
}
