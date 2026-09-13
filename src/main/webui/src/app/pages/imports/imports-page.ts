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
 * stands, animateurs — then the stand matrix, which is not a referential import
 * at all: it writes opening hours onto stands that already exist, and only
 * makes sense once the three before it are done. The scenario file closes the
 * list, apart from the rest: it does not fill an edition, it replaces one.</p>
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
  protected readonly colonnesTypologies = $localize`:@@imports.typologies.colonnes:Colonnes « id » et « libelle », obligatoires ; « ninja » facultative pour la typologie polyvalente.`;
  protected readonly aideTypologies = $localize`:@@imports.typologies.aide:L'identifiant est ce que les stands et les compétences citeront : court et stable. Un identifiant déjà connu voit son libellé mis à jour.`;
  protected readonly colonnesEmplacements = $localize`:@@imports.emplacements.colonnes:Colonnes « id » et « nom », obligatoires ; « latitude » et « longitude » facultatives.`;
  protected readonly aideEmplacements = $localize`:@@imports.emplacements.aide:Sans coordonnées, l'emplacement existe mais ne pèse pas sur les distances entre stands d'une même journée.`;
  protected readonly colonnesStands = $localize`:@@imports.stands.colonnes:Colonnes « id », « nom » et « typologies », obligatoires ; « effectifMin » et « effectifMax » facultatives.`;
  protected readonly aideStands = $localize`:@@imports.stands.aide:Plusieurs typologies se séparent par « | ». Sans effectif, le stand tient à une personne. Une typologie inconnue est créée, et annoncée avant l'écriture.`;

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
