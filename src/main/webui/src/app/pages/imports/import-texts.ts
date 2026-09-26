// The words each referential card needs — its columns and its one piece of
// advice — kept apart so the Importer tab and the Importer dialog of a
// referential screen say the same thing. Runtime functions: `$localize`
// resolves after the catalog is loaded.

import { ReferentielImportTarget } from '../../core/models';

export interface ReferentialImportTexts {
  colonnes: string;
  aide: string;
}

export function referentialImportTexts(target: ReferentielImportTarget): ReferentialImportTexts {
  switch (target) {
    case 'TYPOLOGIES':
      return {
        colonnes: $localize`:@@imports.typologies.colonnes:Colonnes « code » et « libelle », obligatoires ; « ninja » facultative pour la typologie polyvalente. Un code vide désigne la ligne par son libellé.`,
        aide: $localize`:@@imports.typologies.aide:Le code est ce que les stands et les compétences citeront : court et stable. Un code déjà connu voit son libellé mis à jour ; l'identifiant, lui, est attribué par l'application.`,
      };
    case 'EMPLACEMENTS':
      return {
        colonnes: $localize`:@@imports.emplacements.colonnes:Colonnes « code » et « nom », obligatoires ; « latitude » et « longitude » facultatives. Un code vide désigne la ligne par son nom.`,
        aide: $localize`:@@imports.emplacements.aide:Sans coordonnées, l'emplacement existe mais ne pèse pas sur les distances entre stands d'une même journée.`,
      };
    case 'STANDS':
      return {
        colonnes: $localize`:@@imports.stands.colonnes:Colonnes « code », « nom » et « typologies » (par leur code, sinon leur libellé), obligatoires ; « effectifMin » et « effectifMax » facultatives. Un code vide désigne la ligne par son nom.`,
        aide: $localize`:@@imports.stands.aide:Plusieurs typologies se séparent par « | ». Sans effectif, le stand tient à une personne. Une typologie inconnue est créée, et annoncée avant l'écriture.`,
      };
    case 'CRENEAUX':
      return {
        colonnes: $localize`:@@imports.creneaux.colonnes:Colonnes « date », « heureDebut » et « heureFin », obligatoires ; « couverturePause » facultative.`,
        aide: $localize`:@@imports.creneaux.aide:Un créneau se reconnaît à sa date et à ses deux heures : rejoué, le même fichier met à jour au lieu de doubler la grille. Une fin avant le début passe minuit.`,
      };
    case 'JOURNEES_TYPES':
      return {
        colonnes: $localize`:@@imports.journeesTypes.colonnes:Colonnes « nom » et « vacations », obligatoires ; « dates » facultative.`,
        aide: $localize`:@@imports.journeesTypes.aide:Les vacations tiennent sur une ligne, « 09:00-12:00, 12:00-13:00 R, 14:00-20:00 », R pour un relais repas. Les créneaux ne bougent qu'à l'application du calendrier.`,
      };
  }
}
