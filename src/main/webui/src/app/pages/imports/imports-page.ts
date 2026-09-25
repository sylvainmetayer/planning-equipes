import {
  ChangeDetectionStrategy,
  Component,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ActivatedRoute } from '@angular/router';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { ImportAnimateursPage } from '../import-animateurs/import-animateurs-page';
import { ImportGrilleStandsPage } from '../import-grille-stands/import-grille-stands-page';
import { ImportReferentielCard } from './import-referentiel-card';
import { ImportScenarioCard } from './import-scenario-card';
import { OngletImports, readOngletImports } from './imports';

/**
 * « Imports » : every file that fills an edition, under one entry of the menu
 * instead of one entry per file.
 *
 * <p>The tabs follow the order the data is entered — typologies, emplacements,
 * stands, the timeslot grid, the day templates, animateurs — then the stand
 * matrix, which is not a referential import at all: it writes opening hours
 * onto stands that already exist, and only makes sense once the ones before it
 * are done. The dates come before the animateurs on purpose: an imported off
 * day only survives in an edition that already carries the matching timeslot.
 * The scenario file closes the list, apart from the rest: it does not fill an
 * edition, it replaces one.</p>
 */
@Component({
  selector: 'app-imports-page',
  imports: [
    MatButtonToggleModule,
    MatCardModule,
    MatIconModule,
    ImportReferentielCard,
    ImportAnimateursPage,
    ImportGrilleStandsPage,
    ImportScenarioCard,
  ],
  templateUrl: './imports-page.html',
  styleUrl: '../../../styles/import-animateurs.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partials it hosts.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ImportsPage {
  private readonly route = inject(ActivatedRoute);

  protected readonly onglet = signal<OngletImports>('typologies');

  /* The words each referential tab needs, kept here so the shared card stays about the mechanism. */
  protected readonly colonnesTypologies = $localize`:@@imports.typologies.colonnes:Colonnes « code » et « libelle », obligatoires ; « ninja » facultative pour la typologie polyvalente.`;
  protected readonly aideTypologies = $localize`:@@imports.typologies.aide:Le code est ce que les stands et les compétences citeront : court et stable. Un code déjà connu voit son libellé mis à jour ; l'identifiant, lui, est attribué par l'application.`;
  protected readonly colonnesEmplacements = $localize`:@@imports.emplacements.colonnes:Colonnes « code » et « nom », obligatoires ; « latitude » et « longitude » facultatives.`;
  protected readonly aideEmplacements = $localize`:@@imports.emplacements.aide:Sans coordonnées, l'emplacement existe mais ne pèse pas sur les distances entre stands d'une même journée.`;
  protected readonly colonnesStands = $localize`:@@imports.stands.colonnes:Colonnes « code », « nom » et « typologies » (par leur code), obligatoires ; « effectifMin » et « effectifMax » facultatives.`;
  protected readonly aideStands = $localize`:@@imports.stands.aide:Plusieurs typologies se séparent par « | ». Sans effectif, le stand tient à une personne. Une typologie inconnue est créée, et annoncée avant l'écriture.`;
  protected readonly colonnesCreneaux = $localize`:@@imports.creneaux.colonnes:Colonnes « date », « heureDebut » et « heureFin », obligatoires ; « couverturePause » facultative.`;
  protected readonly aideCreneaux = $localize`:@@imports.creneaux.aide:Un créneau se reconnaît à sa date et à ses deux heures : rejoué, le même fichier met à jour au lieu de doubler la grille. Une fin avant le début passe minuit.`;
  protected readonly colonnesJourneesTypes = $localize`:@@imports.journeesTypes.colonnes:Colonnes « nom » et « vacations », obligatoires ; « dates » facultative.`;
  protected readonly aideJourneesTypes = $localize`:@@imports.journeesTypes.aide:Les vacations tiennent sur une ligne, « 09:00-12:00, 12:00-13:00 R, 14:00-20:00 », R pour un relais repas. Les créneaux ne bougent qu'à l'application du calendrier.`;

  constructor() {
    this.onglet.set(readOngletImports(this.route.snapshot.queryParamMap.get('onglet')));
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'typologies' ? null : this.onglet(),
    }));
  }

  protected changerOnglet(onglet: OngletImports): void {
    this.onglet.set(onglet);
  }
}
