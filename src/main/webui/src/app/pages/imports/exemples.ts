// The scenarios bundled with the application, as an organiser reads them: a
// name, one sentence (its size, its days, what it is about) and the family it
// belongs to. The server lists file names (`GET /api/planning/scenarios`);
// none of them ever reaches the screen. Pure functions, called at runtime so
// `$localize` resolves after the catalog is loaded.

/** Why somebody would load it: to see the application at work, to try one case, to push it to its limits. */
export type ExampleFamily = 'decouvrir' | 'tester' | 'extremes';

export const EXAMPLE_FAMILIES: readonly ExampleFamily[] = ['decouvrir', 'tester', 'extremes'];

/** One bundled scenario, ready to be offered. */
export interface Exemple {
  /** The file name the server loads it by — never shown. */
  name: string;
  famille: ExampleFamily;
  libelle: string;
  /** Its size, its days and what it is about, in one sentence. */
  phrase: string;
  /** Its rank inside its family. */
  rang: number;
}

export interface GroupeExemples {
  famille: ExampleFamily;
  titre: string;
  exemples: Exemple[];
}

export function familyTitle(famille: ExampleFamily): string {
  switch (famille) {
    case 'decouvrir':
      return $localize`:@@exemples.famille.decouvrir:Pour découvrir`;
    case 'tester':
      return $localize`:@@exemples.famille.tester:Pour tester un cas`;
    case 'extremes':
      return $localize`:@@exemples.famille.extremes:Extrêmes`;
  }
}

/** « 16 jour(s) · 65 stand(s) · 153 animateur(s) ». */
function taille(jours: number, stands: number, animateurs: number): string {
  return $localize`:@@exemples.taille:${jours}:jours: jour(s) · ${stands}:stands: stand(s) · ${animateurs}:animateurs: animateur(s)`;
}

function phrase(size: string | null, sujet: string): string {
  return size === null ? sujet : `${size} — ${sujet}`;
}

interface Fiche {
  famille: ExampleFamily;
  rang: number;
  libelle: string;
  sujet: string;
  size: [number, number, number] | null;
}

/** The hand-written fixtures, each described by what it shows. */
function fixtures(): Record<string, Fiche> {
  return {
    'festival-realiste-canicule.yaml': {
      famille: 'decouvrir',
      rang: 1,
      libelle: $localize`:@@exemples.canicule.libelle:Festival réaliste — canicule`,
      sujet: $localize`:@@exemples.canicule.sujet:une édition réelle anonymisée, chargée dans une édition à son nom`,
      size: [16, 65, 153],
    },
    'festival-hivernal.yaml': {
      famille: 'decouvrir',
      rang: 2,
      libelle: $localize`:@@exemples.hivernal.libelle:Festival hivernal`,
      sujet: $localize`:@@exemples.hivernal.sujet:une édition réelle anonymisée, écrite en vacations avec relèves du soir`,
      size: [16, 65, 153],
    },
    'scenario-complet.yaml': {
      famille: 'decouvrir',
      rang: 3,
      libelle: $localize`:@@exemples.complet.libelle:Festival de douze jours`,
      sujet: $localize`:@@exemples.complet.sujet:matin, après-midi et soirée chaque jour`,
      size: [12, 21, 152],
    },
    'scenario.yml': {
      famille: 'decouvrir',
      rang: 4,
      libelle: $localize`:@@exemples.minimal.libelle:Le plus petit planning`,
      sujet: $localize`:@@exemples.minimal.sujet:pour voir chaque écran se remplir en quelques secondes`,
      size: [1, 2, 3],
    },
    'scenario-avec-erreur-planning.yaml': {
      famille: 'tester',
      rang: 1,
      libelle: $localize`:@@exemples.erreurs.libelle:Six erreurs d'ouverture`,
      sujet: $localize`:@@exemples.erreurs.sujet:infaisable exprès, pour lire le diagnostic`,
      size: [3, 6, 5],
    },
    'scenario-continu.yaml': {
      famille: 'tester',
      rang: 2,
      libelle: $localize`:@@exemples.continu.libelle:Grille continue`,
      sujet: $localize`:@@exemples.continu.sujet:des vacations qui se touchent`,
      size: [12, 21, 178],
    },
    'scenario-continu-avec-coupure.yaml': {
      famille: 'tester',
      rang: 3,
      libelle: $localize`:@@exemples.coupure.libelle:Grille continue avec coupure repas`,
      sujet: $localize`:@@exemples.coupure.sujet:la même grille, avec la coupure repas`,
      size: [12, 21, 178],
    },
    'scenario-contraintes.yaml': {
      famille: 'tester',
      rang: 4,
      libelle: $localize`:@@exemples.contraintes.libelle:Règles réglées`,
      sujet: $localize`:@@exemples.contraintes.sujet:des poids et des règles désactivées`,
      size: [1, 1, 2],
    },
    'scenario-horaires-recurrents.yaml': {
      famille: 'tester',
      rang: 5,
      libelle: $localize`:@@exemples.horaires.libelle:Horaires récurrents`,
      sujet: $localize`:@@exemples.horaires.sujet:des stands qui ferment à des heures différentes`,
      size: [3, 3, 1],
    },
    'scenario-parametres-optionnels.yaml': {
      famille: 'tester',
      rang: 6,
      libelle: $localize`:@@exemples.parametres.libelle:Paramètres facultatifs`,
      sujet: $localize`:@@exemples.parametres.sujet:paramètres légaux et du solveur écrits dans le fichier`,
      size: [1, 1, 1],
    },
    'scenario-sans-postes.yaml': {
      famille: 'tester',
      rang: 7,
      libelle: $localize`:@@exemples.sansPostes.libelle:Sièges déduits des stands`,
      sujet: $localize`:@@exemples.sansPostes.sujet:aucun siège écrit, tous déduits des effectifs`,
      size: [1, 2, 1],
    },
    'scenario-typologies.yaml': {
      famille: 'tester',
      rang: 8,
      libelle: $localize`:@@exemples.typologies.libelle:Typologies`,
      sujet: $localize`:@@exemples.typologies.sujet:une typologie déjà connue et une nouvelle`,
      size: [1, 1, 1],
    },
  };
}

/** What each rung of the ladder adds, by its number. */
function rungs(): Record<number, string> {
  return {
    1: $localize`:@@exemples.gamme.1:le plus petit plan`,
    2: $localize`:@@exemples.gamme.2:un relais pour la pause de midi`,
    3: $localize`:@@exemples.gamme.3:compétences et souhaits`,
    4: $localize`:@@exemples.gamme.4:sièges écrits à la main, jours d'indisponibilité`,
    5: $localize`:@@exemples.gamme.5:des mineurs, dont une majorité acquise en cours d'événement`,
    6: $localize`:@@exemples.gamme.6:ajustements manuels`,
    7: $localize`:@@exemples.gamme.7:horaires récurrents des stands`,
    8: $localize`:@@exemples.gamme.8:relais de midi à effectif réduit`,
    9: $localize`:@@exemples.gamme.9:typologie ninja et emplacements`,
    10: $localize`:@@exemples.gamme.10:plusieurs journées types`,
    11: $localize`:@@exemples.gamme.11:une semaine à cheval sur deux, des nuits`,
    12: $localize`:@@exemples.gamme.12:une semaine complète avec un jour férié`,
    13: $localize`:@@exemples.gamme.13:des stands épuisants`,
    14: $localize`:@@exemples.gamme.14:la relève des repas`,
    15: $localize`:@@exemples.gamme.15:deux week-ends`,
    16: $localize`:@@exemples.gamme.16:mineurs, jour férié et nocturnes`,
    17: $localize`:@@exemples.gamme.17:des règles désactivées`,
    18: $localize`:@@exemples.gamme.18:effectifs par fenêtre d'ouverture`,
    19: $localize`:@@exemples.gamme.19:saisonniers et plages d'ouverture`,
    20: $localize`:@@exemples.gamme.20:un festival type`,
    21: $localize`:@@exemples.gamme.21:un mois en journées types`,
    22: $localize`:@@exemples.gamme.22:un mois de week-ends`,
    23: $localize`:@@exemples.gamme.23:ajustements manuels et souhaits`,
    24: $localize`:@@exemples.gamme.24:mineurs et emplacements`,
    25: $localize`:@@exemples.gamme.25:une édition complète`,
    26: $localize`:@@exemples.gamme.26:infaisable : trop peu d'animateurs`,
    27: $localize`:@@exemples.gamme.27:infaisable : affectation forcée un jour d'indisponibilité`,
    28: $localize`:@@exemples.gamme.28:infaisable : six jours de suite`,
    29: $localize`:@@exemples.gamme.29:infaisable : des mineurs en soirée`,
    30: $localize`:@@exemples.gamme.30:infaisable : la coupure repas`,
  };
}

/** What each extreme case pushes, by its number. */
function extremes(): Record<number, string> {
  return {
    1: $localize`:@@exemples.extreme.1:bien plus d'animateurs que de sièges`,
    2: $localize`:@@exemples.extreme.2:un mois entier`,
    3: $localize`:@@exemples.extreme.3:une saison`,
    4: $localize`:@@exemples.extreme.4:une saison sans relâche`,
    5: $localize`:@@exemples.extreme.5:un seul jour très dense`,
    6: $localize`:@@exemples.extreme.6:24 h sur 24`,
    7: $localize`:@@exemples.extreme.7:des créneaux d'une heure`,
    8: $localize`:@@exemples.extreme.8:un stand de 200 places`,
    9: $localize`:@@exemples.extreme.9:tous les excès cumulés`,
    10: $localize`:@@exemples.extreme.10:sans animateur`,
    11: $localize`:@@exemples.extreme.11:un siège pour 1 000 animateurs`,
    12: $localize`:@@exemples.extreme.12:uniquement des mineurs`,
    13: $localize`:@@exemples.extreme.13:2 000 ajustements manuels`,
    14: $localize`:@@exemples.extreme.14:des stands jamais ouverts`,
    15: $localize`:@@exemples.extreme.15:sans créneau`,
  };
}

/** `gamme-05-2j-4stands-8animateurs-mineurs.yaml` → its number and, when the name carries it, its size. */
function readNumbered(name: string, prefix: string): { rang: number; size: string | null } | null {
  const match = new RegExp(`^${prefix}-(\\d+)(?:-(\\d+)j-(\\d+)stands-(\\d+)animateurs)?`).exec(
    name,
  );
  if (!match) {
    return null;
  }
  const size = match[2] ? taille(Number(match[2]), Number(match[3]), Number(match[4])) : null;
  return { rang: Number(match[1]), size };
}

function capitalize(text: string): string {
  return text.charAt(0).toLocaleUpperCase() + text.slice(1);
}

/** A file this catalogue does not know yet: its name without extension, words apart, never the file itself. */
function unknown(name: string): Exemple {
  const words = name.replace(/\.ya?ml$/i, '').replace(/[-_]+/g, ' ');
  return {
    name,
    famille: 'tester',
    libelle: capitalize(words),
    phrase: $localize`:@@exemples.inconnu:Un scénario livré avec l'application`,
    rang: 1000,
  };
}

/** How the screen names and describes one bundled scenario. */
export function describeExample(name: string): Exemple {
  const fiche = fixtures()[name];
  if (fiche) {
    return {
      name,
      famille: fiche.famille,
      libelle: fiche.libelle,
      phrase: phrase(fiche.size ? taille(...fiche.size) : null, fiche.sujet),
      rang: fiche.rang,
    };
  }
  const rung = readNumbered(name, 'gamme');
  const subjectOfRung = rung ? rungs()[rung.rang] : undefined;
  if (rung && subjectOfRung) {
    const numero = rung.rang;
    return {
      name,
      famille: 'tester',
      libelle: $localize`:@@exemples.gamme.libelle:Gamme ${numero}:numero: — ${subjectOfRung}:sujet:`,
      phrase: phrase(
        rung.size,
        $localize`:@@exemples.gamme.phrase:barreau ${numero}:numero: de la gamme de tests du solveur`,
      ),
      rang: 100 + numero,
    };
  }
  const extreme = readNumbered(name, 'extreme');
  const subjectOfExtreme = extreme ? extremes()[extreme.rang] : undefined;
  if (extreme && subjectOfExtreme) {
    return {
      name,
      famille: 'extremes',
      libelle: capitalize(subjectOfExtreme),
      phrase: phrase(
        extreme.size,
        $localize`:@@exemples.extreme.phrase:un cas limite, pour éprouver le solveur`,
      ),
      rang: extreme.rang,
    };
  }
  return unknown(name);
}

/** The bundled scenarios, by family in the order a newcomer needs them, each family by rank. */
export function groupExamples(names: readonly string[]): GroupeExemples[] {
  const exemples = names.filter((name) => /\.ya?ml$/i.test(name)).map(describeExample);
  return EXAMPLE_FAMILIES.map((famille) => ({
    famille,
    titre: familyTitle(famille),
    exemples: exemples
      .filter((exemple) => exemple.famille === famille)
      .sort((a, b) => a.rang - b.rang || a.libelle.localeCompare(b.libelle)),
  })).filter((groupe) => groupe.exemples.length > 0);
}

/** The example offered first: the realistic one when it is shipped, else the first to discover. */
export function defaultExample(groupes: readonly GroupeExemples[]): string | null {
  const all = groupes.flatMap((groupe) => groupe.exemples);
  return (
    all.find((exemple) => exemple.name === 'festival-realiste-canicule.yaml')?.name ??
    all[0]?.name ??
    null
  );
}
