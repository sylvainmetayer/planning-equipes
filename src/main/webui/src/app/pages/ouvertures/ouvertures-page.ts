import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import {
  AnomalieOuverture,
  CelluleJourOuverture,
  LigneStandOuverture,
  RapportOuvertures
} from '../../core/models';
import {
  anomaliesParStand,
  classeCellule,
  dureeCourte,
  filtrerStands,
  FiltreOuvertures,
  iconeAnomalie,
  largeurPourcent,
  synthese
} from './ouvertures';

/**
 * Read-only stand × jour grid of the opening schedule actually in force, so an
 * administrator can validate it visually before spending minutes on a solve.
 *
 * <p>Everything shown comes from `GET /api/ouvertures-stands`, which builds it
 * server-side from the very postes `PlanningService.construirePostes` would hand
 * the solver — recurring horaires expanded, dated exceptions applied, windows
 * clamped to each créneau. Deliberately not recomputed here: a validation screen
 * that offers a second interpretation of the data validates nothing.
 */
@Component({
  selector: 'app-ouvertures-page',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatInputModule,
    MatIconModule,
    MatTooltipModule
  ],
  templateUrl: './ouvertures-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class OuverturesPage {
  private readonly api = inject(ApiService);
  private readonly crud = inject(ReferenceCrudService);

  protected readonly rapport = signal<RapportOuvertures | null>(null);
  protected readonly chargement = signal(true);
  protected readonly filtre = signal<FiltreOuvertures>('TOUS');
  protected readonly recherche = signal('');

  protected readonly synthese = computed(() => {
    const rapport = this.rapport();
    return rapport ? synthese(rapport) : null;
  });
  protected readonly lignes = computed<LigneStandOuverture[]>(() => {
    const rapport = this.rapport();
    return rapport ? filtrerStands(rapport, this.filtre(), this.recherche()) : [];
  });
  private readonly anomaliesParStand = computed(() => anomaliesParStand(this.rapport()?.anomalies ?? []));

  constructor() {
    void this.recharger();
  }

  protected async recharger(): Promise<void> {
    this.chargement.set(true);
    try {
      this.rapport.set(await this.api.get<RapportOuvertures>('/api/ouvertures-stands'));
    } catch (error) {
      this.crud.reportError(error);
    } finally {
      this.chargement.set(false);
    }
  }

  protected readonly largeurPourcent = largeurPourcent;
  protected readonly classeCellule = classeCellule;
  protected readonly iconeAnomalie = iconeAnomalie;

  protected duree(minutes: number): string {
    return dureeCourte(minutes, {
      heures: $localize`:@@ouvertures.duree.heures:h`,
      minutes: $localize`:@@ouvertures.duree.minutes:min`
    });
  }

  /** `2026-07-08` → `08/07`, short enough for a dozen columns. */
  protected libelleJour(date: string): string {
    const [, mois, jour] = date.split('-');
    return `${jour}/${mois}`;
  }

  protected anomaliesDe(standId: string): AnomalieOuverture[] {
    return this.anomaliesParStand().get(standId) ?? [];
  }

  protected infobulleStand(ligne: LigneStandOuverture): string {
    const anomalies = this.anomaliesDe(ligne.standId);
    return anomalies.length === 0 ? '' : anomalies.map((anomalie) => anomalie.message).join('\n');
  }

  /** Everything a cell says, for its tooltip — state, windows, decisive layer, postes. */
  protected infobulleCellule(cellule: CelluleJourOuverture): string {
    const lignes: string[] = [this.libelleEtat(cellule)];
    if (cellule.fenetres.length > 0) {
      lignes.push(
        cellule.fenetres
          .map((fenetre) => `${this.heure(fenetre.heureDebut)} → ${this.heure(fenetre.heureFin)}`)
          .join(', ')
      );
    }
    lignes.push(
      $localize`:@@ouvertures.tooltip.ouvert:Ouvert ${this.duree(cellule.minutesOuvertes)}:ouvert: sur ${this.duree(cellule.minutesAmplitude)}:amplitude:`
    );
    lignes.push($localize`:@@ouvertures.tooltip.postes:${cellule.postes}:postes: poste(s) généré(s)`);
    lignes.push(this.libelleSource(cellule));
    return lignes.join('\n');
  }

  protected libelleEtat(cellule: CelluleJourOuverture): string {
    switch (cellule.etat) {
      case 'OUVERT_TOTAL':
        return $localize`:@@ouvertures.etat.total:Ouvert toute l'amplitude`;
      case 'OUVERT_PARTIEL':
        return $localize`:@@ouvertures.etat.partiel:Ouvert partiellement`;
      case 'FERME':
        return $localize`:@@ouvertures.etat.ferme:Fermé`;
    }
  }

  protected libelleSource(cellule: CelluleJourOuverture): string {
    switch (cellule.source) {
      case 'DEFAUT':
        return $localize`:@@ouvertures.source.defaut:Aucune règle ni exception : ouvert par défaut`;
      case 'REGLE':
        return $localize`:@@ouvertures.source.regle:Décidé par une règle d'horaire récurrente`;
      case 'EXCEPTION':
        return $localize`:@@ouvertures.source.exception:Décidé par une exception datée, qui prime sur les règles`;
    }
  }

  /** `09:00:00` → `09:00`. */
  protected heure(valeur: string): string {
    return valeur.length > 5 ? valeur.slice(0, 5) : valeur;
  }
}
