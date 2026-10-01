// The words of the comparison of two editions, pure: the summary sentence,
// the labels of the families, changes, matching keys, fields and legal
// parameters, and where « ouvrir dans l'édition » leads for each family.

import {
  DeltaChange,
  DeltaFamily,
  DeltaLine,
  DeltaMatch,
  DeltaValueLine,
  EditionDelta,
} from '../../core/models';

/** The families drawn as entity tables, in the order the screen shows them. */
export type EntityFamily = 'STAND' | 'ANIMATEUR' | 'TYPOLOGIE' | 'EMPLACEMENT' | 'JOURNEE_TYPE';

export const ENTITY_FAMILIES: readonly EntityFamily[] = [
  'STAND',
  'ANIMATEUR',
  'TYPOLOGIE',
  'EMPLACEMENT',
  'JOURNEE_TYPE',
];

/** The lines of one entity family. */
export function entityLines(delta: EditionDelta, family: EntityFamily): DeltaLine[] {
  switch (family) {
    case 'STAND':
      return delta.stands;
    case 'ANIMATEUR':
      return delta.animateurs;
    case 'TYPOLOGIE':
      return delta.typologies;
    case 'EMPLACEMENT':
      return delta.emplacements;
    case 'JOURNEE_TYPE':
      return delta.journeesTypes;
  }
}

export function familyTitle(family: DeltaFamily): string {
  switch (family) {
    case 'STAND':
      return $localize`:@@comparerEditions.famille.stands:Stands`;
    case 'ANIMATEUR':
      return $localize`:@@comparerEditions.famille.animateurs:Animateurs`;
    case 'TYPOLOGIE':
      return $localize`:@@comparerEditions.famille.typologies:Typologies`;
    case 'EMPLACEMENT':
      return $localize`:@@comparerEditions.famille.emplacements:Emplacements`;
    case 'JOURNEE_TYPE':
      return $localize`:@@comparerEditions.famille.journeesTypes:Journées types`;
    case 'CRENEAU':
      return $localize`:@@comparerEditions.famille.creneaux:Créneaux`;
    case 'PARAMETRE':
      return $localize`:@@comparerEditions.famille.parametres:Paramètres`;
    case 'AJUSTEMENT':
      return $localize`:@@comparerEditions.famille.ajustements:Ajustements`;
  }
}

export function changeLabel(change: DeltaChange): string {
  switch (change) {
    case 'ADDED':
      return $localize`:@@comparerEditions.changement.ajoute:Ajouté`;
    case 'REMOVED':
      return $localize`:@@comparerEditions.changement.retire:Retiré`;
    case 'MODIFIED':
      return $localize`:@@comparerEditions.changement.modifie:Modifié`;
  }
}

/** For an animateur, « arrivé » and « parti » rather than added and removed. */
export function animateurChangeLabel(change: DeltaChange): string {
  switch (change) {
    case 'ADDED':
      return $localize`:@@comparerEditions.changement.arrive:Arrivé`;
    case 'REMOVED':
      return $localize`:@@comparerEditions.changement.parti:Parti`;
    case 'MODIFIED':
      return $localize`:@@comparerEditions.changement.modifie:Modifié`;
  }
}

/**
 * The key two rows were matched on. `NOM` is the fallback the screen flags —
 * except for a day template, which has no code: its name is its key.
 */
export function matchLabel(match: DeltaMatch | null, family?: EntityFamily): string {
  switch (match) {
    case 'CODE':
      return $localize`:@@comparerEditions.rapprochement.code:par code`;
    case 'NOM':
      return family === 'JOURNEE_TYPE'
        ? $localize`:@@comparerEditions.rapprochement.nomSeul:par nom`
        : $localize`:@@comparerEditions.rapprochement.nom:par nom, faute de code`;
    case 'EMAIL':
      return $localize`:@@comparerEditions.rapprochement.email:par e-mail`;
    case 'IDENTITE':
      return $localize`:@@comparerEditions.rapprochement.identite:par prénom, nom et date de naissance`;
    case 'POSITION':
      return $localize`:@@comparerEditions.rapprochement.position:par position`;
    case null:
      return '';
  }
}

/** Whether a line rests on the name-for-want-of-a-code fallback the screen flags. */
export function flaggedByName(family: EntityFamily, line: DeltaLine): boolean {
  return line.matching === 'NOM' && family !== 'JOURNEE_TYPE';
}

/** The name of a field as the forms show it; an unknown one as the server wrote it. */
export function fieldLabel(field: string): string {
  const labels: Record<string, string> = {
    code: $localize`:@@comparerEditions.champ.code:code`,
    nom: $localize`:@@comparerEditions.champ.nom:nom`,
    label: $localize`:@@comparerEditions.champ.label:libellé`,
    ninja: $localize`:@@comparerEditions.champ.ninja:polyvalente`,
    maxCreneauxParAnimateur: $localize`:@@comparerEditions.champ.quota:plafond par animateur`,
    description: $localize`:@@comparerEditions.champ.description:description`,
    latitude: $localize`:@@comparerEditions.champ.latitude:latitude`,
    longitude: $localize`:@@comparerEditions.champ.longitude:longitude`,
    typologiesProposees: $localize`:@@comparerEditions.champ.typologies:typologies`,
    effectifMin: $localize`:@@comparerEditions.champ.effectifMin:effectif minimum`,
    effectifMax: $localize`:@@comparerEditions.champ.effectifMax:effectif maximum`,
    reserveMajeurs: $localize`:@@comparerEditions.champ.reserveMajeurs:majeurs seulement`,
    premium: $localize`:@@comparerEditions.champ.premium:premium`,
    niveauEffort: $localize`:@@comparerEditions.champ.effort:effort`,
    emplacement: $localize`:@@comparerEditions.champ.emplacement:emplacement`,
    indisponibilites: $localize`:@@comparerEditions.champ.fermetures:fermetures datées`,
    ouvertures: $localize`:@@comparerEditions.champ.ouvertures:ouvertures datées`,
    horaires: $localize`:@@comparerEditions.champ.horaires:horaires`,
    prenom: $localize`:@@comparerEditions.champ.prenom:prénom`,
    dateNaissance: $localize`:@@comparerEditions.champ.dateNaissance:date de naissance`,
    email: $localize`:@@comparerEditions.champ.email:e-mail`,
    manager: $localize`:@@comparerEditions.champ.manager:manager`,
    competences: $localize`:@@comparerEditions.champ.competences:compétences`,
    souhaits: $localize`:@@comparerEditions.champ.souhaits:souhaits`,
    vacations: $localize`:@@comparerEditions.champ.vacations:vacations`,
    heureFin: $localize`:@@comparerEditions.champ.heureFin:heure de fin`,
    couverturePause: $localize`:@@comparerEditions.champ.relais:relais repas`,
  };
  return labels[field] ?? field;
}

export function fieldsLabel(fields: readonly string[]): string {
  return fields.map(fieldLabel).join(', ');
}

/** A legal parameter as the Règles screen names it; a constraint by its catalogue label. */
export function valueLabel(line: DeltaValueLine): string {
  if (line.label) {
    return line.label;
  }
  const legaux: Record<string, string> = {
    dureeHebdomadaireMaxMinutes: $localize`:@@comparerEditions.legal.hebdo:Durée hebdomadaire maximale (minutes)`,
    dureeHebdomadaireMaxMineurMinutes: $localize`:@@comparerEditions.legal.hebdoMineur:Durée hebdomadaire maximale d'un mineur (minutes)`,
    dureeVacationMaxMinutes: $localize`:@@comparerEditions.legal.vacation:Durée maximale d'une vacation (minutes)`,
    reposQuotidienMinimalMinutes: $localize`:@@comparerEditions.legal.repos:Repos quotidien minimal (minutes)`,
    dureePauseMinutes: $localize`:@@comparerEditions.legal.pause:Durée de la pause (minutes)`,
    coupureRepasMinutes: $localize`:@@comparerEditions.legal.coupure:Coupure repas (minutes)`,
    coupureRepasMidiDebut: $localize`:@@comparerEditions.legal.midiDebut:Début de la fenêtre repas du midi`,
    coupureRepasMidiFin: $localize`:@@comparerEditions.legal.midiFin:Fin de la fenêtre repas du midi`,
    coupureRepasSoirDebut: $localize`:@@comparerEditions.legal.soirDebut:Début de la fenêtre repas du soir`,
    coupureRepasSoirFin: $localize`:@@comparerEditions.legal.soirFin:Fin de la fenêtre repas du soir`,
    heureDebutSoiree: $localize`:@@comparerEditions.legal.soiree:Début de la soirée`,
    INDISPONIBILITE_FORCEE: $localize`:@@comparerEditions.ajustement.indisponibilite:Indisponibilités forcées`,
    INCOMPATIBILITE: $localize`:@@comparerEditions.ajustement.incompatibilite:Incompatibilités`,
    AFFECTATION_FORCEE: $localize`:@@comparerEditions.ajustement.affectation:Affectations forcées`,
    AFFINITE: $localize`:@@comparerEditions.ajustement.affinite:Affinités`,
    ARRIVEE_GROUPEE: $localize`:@@comparerEditions.ajustement.arriveeGroupee:Arrivées groupées`,
  };
  return legaux[line.key] ?? line.key;
}

/** A value as read: a switch in words; a constraint's is the effective one, never missing. */
export function valueText(line: DeltaValueLine, value: string | null): string {
  if (value === null) {
    return '—';
  }
  if (line.group === 'CONSTRAINT_ACTIVE') {
    return value === 'true'
      ? $localize`:@@comparerEditions.valeur.active:activée`
      : $localize`:@@comparerEditions.valeur.desactivee:désactivée`;
  }
  return value;
}

/** A signed whole number, with a true minus sign: « +3 », « −12 ». */
export function signed(value: number): string {
  if (value > 0) {
    return `+${value}`;
  }
  return value < 0 ? `−${Math.abs(value)}` : '0';
}

/** Hours to the unit, the precision a comparison of two years is read at. */
export function hours(value: number): string {
  return String(Math.round(value));
}

export function ratio(value: number | null): string {
  return value === null ? '—' : `${Math.round(value * 100)} %`;
}

/**
 * « +3 stands, −12 animateurs, +410 heures à pourvoir »: the net change of
 * every family that moved, then the hours to fill. Empty when nothing differs.
 */
export function summaryParts(delta: EditionDelta): string[] {
  const parts: string[] = [];
  for (const count of delta.summary.families) {
    const net = count.added - count.removed;
    switch (count.family) {
      case 'STAND':
        if (net !== 0) {
          parts.push($localize`:@@comparerEditions.resume.stands:${signed(net)}:n: stands`);
        }
        break;
      case 'ANIMATEUR':
        if (net !== 0) {
          parts.push($localize`:@@comparerEditions.resume.animateurs:${signed(net)}:n: animateurs`);
        }
        break;
      case 'TYPOLOGIE':
        if (net !== 0) {
          parts.push($localize`:@@comparerEditions.resume.typologies:${signed(net)}:n: typologies`);
        }
        break;
      case 'EMPLACEMENT':
        if (net !== 0) {
          parts.push(
            $localize`:@@comparerEditions.resume.emplacements:${signed(net)}:n: emplacements`,
          );
        }
        break;
      default:
        break;
    }
  }
  const jours = delta.summary.targetDays - delta.summary.referenceDays;
  if (jours !== 0) {
    parts.push($localize`:@@comparerEditions.resume.jours:${signed(jours)}:n: jours d'ouverture`);
  }
  const heures = Math.round(delta.summary.hoursToFillDifference);
  if (heures !== 0) {
    parts.push($localize`:@@comparerEditions.resume.heures:${signed(heures)}:n: heures à pourvoir`);
  }
  const modifies = delta.summary.families
    .filter((count) => count.family !== 'PARAMETRE' && count.family !== 'AJUSTEMENT')
    .reduce((total, count) => total + count.modified, 0);
  if (modifies > 0) {
    parts.push($localize`:@@comparerEditions.resume.modifies:${modifies}:n: fiches modifiées`);
  }
  const reglages = delta.parametres.length + delta.ajustements.length;
  if (reglages > 0) {
    parts.push($localize`:@@comparerEditions.resume.reglages:${reglages}:n: réglages différents`);
  }
  return parts;
}

/** The families whose rows rest on their name for want of a code: the fallback said once, with its count. */
export function matchedByName(delta: EditionDelta): string[] {
  return delta.summary.families
    .filter((count) => count.matchedByName > 0)
    .map(
      (count) =>
        $localize`:@@comparerEditions.parNom:${familyTitle(count.family)}:famille: : ${count.matchedByName}:n: rapprochés par leur nom`,
    );
}

/** The grid, where a timeslot and a day template are edited. */
export const TIMESLOT_URL = '/creneaux';

/**
 * Where a line leads in the edition holding it: the fiche of a stand or of an
 * animateur, the screen of the other families. `null` for a row without id.
 */
export function ficheUrl(family: EntityFamily | 'CRENEAU', id: string | null): string | null {
  if (id === null) {
    return null;
  }
  switch (family) {
    case 'STAND':
      return `/stands/${encodeURIComponent(id)}`;
    case 'ANIMATEUR':
      return `/animateurs/${encodeURIComponent(id)}`;
    case 'TYPOLOGIE':
      return '/typologies';
    case 'EMPLACEMENT':
      return '/stands?onglet=lieux';
    case 'JOURNEE_TYPE':
    case 'CRENEAU':
      return TIMESLOT_URL;
  }
}
