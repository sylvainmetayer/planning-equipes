// The scenarios bundled with the application, as an organiser reads them: a
// name, one sentence (its size, its days, what it is about) and the family it
// belongs to. The server lists file names (`GET /api/planning/scenarios`);
// none of them ever reaches the screen. Pure functions, called at runtime so
// `$localize` resolves after the catalog is loaded.

/** Why somebody would load it: to see the application at work, or to try one case. */
export type ExampleFamily = 'decouvrir' | 'tester';

export const EXAMPLE_FAMILIES: readonly ExampleFamily[] = ['decouvrir', 'tester'];

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
