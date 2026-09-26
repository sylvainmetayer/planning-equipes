// The pure side of « Règles du planning »: which tab a rule lives on, in which
// order a tab lists its rules, what article founds one, and how each setting a
// rule reads is edited on the rule's own row. Kept out of the page so it is
// unit tested without rendering.

import { ConstraintView, ParametresLegaux, ParametresQualite } from '../../core/models';
import { segmenterArticles } from '../../core/legifrance';

/** The three tabs, and the values of the `onglet` query param. */
export type OngletRegles = 'legal' | 'qualite' | 'calcul';

export const ONGLETS_REGLES: readonly OngletRegles[] = ['legal', 'qualite', 'calcul'];

/** Reads the `onglet` query param; anything unknown is `null`, so the page can fall back on the rule it was asked. */
export function readOngletRegles(value: string | null): OngletRegles | null {
  return (ONGLETS_REGLES as readonly string[]).includes(value ?? '')
    ? (value as OngletRegles)
    : null;
}

/**
 * The tab a rule is listed on: every hard rule on « Légal » — the law, the
 * minors' safety, the meal break the event is built on, the assignment
 * itself —, every medium or soft one on « Qualité ». The same split as
 * `ConstraintParameters.ongletOf` on the server, which links a setting to it.
 */
export function ongletOfRule(rule: Pick<ConstraintView, 'niveau'>): 'legal' | 'qualite' {
  return rule.niveau === 'HARD' ? 'legal' : 'qualite';
}

/**
 * The order the categories are read in: the Code du travail first, then what
 * the organiser holds as hard, then the assignment and the hand-written
 * exceptions; on « Qualité », the dosed rules before the preferences.
 */
const ORDRE_CATEGORIES: readonly string[] = [
  'Légal (temps de travail)',
  'Légal (mineurs)',
  'Sécurité (mineurs)',
  'Organisation (repas)',
  'Affectation',
  'Contraintes ad hoc',
  'Verrouillage du planning',
  "Qualité d'organisation",
  'Préférences',
];

function rangCategorie(categorie: string): number {
  const rang = ORDRE_CATEGORIES.indexOf(categorie);
  return rang === -1 ? ORDRE_CATEGORIES.length : rang;
}

/**
 * The rules of one tab, category by category in the reading order above, and
 * in the catalogue's own order inside a category — a stable sort keeps it.
 */
export function rulesOfTab(
  contraintes: readonly ConstraintView[],
  onglet: 'legal' | 'qualite',
): ConstraintView[] {
  return contraintes
    .filter((rule) => ongletOfRule(rule) === onglet)
    .map((rule, index) => ({ rule, index }))
    .sort(
      (a, b) =>
        rangCategorie(a.rule.categorie) - rangCategorie(b.rule.categorie) || a.index - b.index,
    )
    .map(({ rule }) => rule);
}

/**
 * The articles a rule's description cites, in order and without repeats —
 * `L3121-20`, `L3162-1`. Empty for a rule no article founds, which the
 * « Article » column then fills with its category.
 */
export function articlesOf(description: string): string[] {
  const articles: string[] = [];
  for (const segment of segmenterArticles(description)) {
    if (segment.url && !articles.includes(segment.text)) {
      articles.push(segment.text);
    }
  }
  return articles;
}

/* ------------------------------------------------------------------------ */
/* The settings a rule reads, edited on its row                             */
/* ------------------------------------------------------------------------ */

/**
 * How one setting is shown and typed:
 * - `heures` — stored in minutes, typed in hours (a week of 48 h, not 2 880 min);
 * - `minutes`, `entier`, `decimal` — typed as stored;
 * - `heure` — a time of day, `HH:mm`;
 * - `plage` — two times of day, a window.
 */
export type SeuilKind = 'heures' | 'minutes' | 'entier' | 'decimal' | 'heure' | 'plage';

export interface SeuilDescriptor {
  /** Which record it is stored in: the legal parameters or the quality thresholds. */
  source: 'legaux' | 'qualite';
  kind: SeuilKind;
  /** The field(s) of that record: two for a window, one otherwise. */
  fields: readonly string[];
  min?: number;
  max?: number;
  step?: number;
}

/**
 * Every setting key `ConstraintParameters` hands out (its `cle`), and how to
 * edit it. A key the server sends and this table does not know is shown read
 * only, with its formatted value — never dropped.
 */
export const SEUILS: Readonly<Record<string, SeuilDescriptor>> = {
  dureeHebdomadaireMaxMinutes: {
    source: 'legaux',
    kind: 'heures',
    fields: ['dureeHebdomadaireMaxMinutes'],
    min: 1,
    max: 48,
    step: 0.5,
  },
  dureeHebdomadaireMaxMineurMinutes: {
    source: 'legaux',
    kind: 'heures',
    fields: ['dureeHebdomadaireMaxMineurMinutes'],
    min: 1,
    max: 35,
    step: 0.5,
  },
  dureePauseMinutes: {
    source: 'legaux',
    kind: 'minutes',
    fields: ['dureePauseMinutes'],
    min: 20,
    step: 5,
  },
  coupureRepasMinutes: {
    source: 'legaux',
    kind: 'minutes',
    fields: ['coupureRepasMinutes'],
    min: 0,
    step: 5,
  },
  coupureRepasMidi: {
    source: 'legaux',
    kind: 'plage',
    fields: ['coupureRepasMidiDebut', 'coupureRepasMidiFin'],
  },
  coupureRepasSoir: {
    source: 'legaux',
    kind: 'plage',
    fields: ['coupureRepasSoirDebut', 'coupureRepasSoirFin'],
  },
  maxEmplacementsDistinctsParJour: {
    source: 'qualite',
    kind: 'entier',
    fields: ['maxEmplacementsDistinctsParJour'],
    min: 1,
    step: 1,
  },
  typologiesDistinctesMax: {
    source: 'qualite',
    kind: 'entier',
    fields: ['typologiesDistinctesMax'],
    min: 1,
    step: 1,
  },
  joursConsecutifsMax: {
    source: 'qualite',
    kind: 'entier',
    fields: ['joursConsecutifsMax'],
    min: 1,
    step: 1,
  },
  vitesseMarcheKmH: {
    source: 'qualite',
    kind: 'decimal',
    fields: ['vitesseMarcheKmH'],
    min: 0.5,
    max: 15,
    step: 0.5,
  },
  facteurDetour: {
    source: 'qualite',
    kind: 'decimal',
    fields: ['facteurDetour'],
    min: 1,
    max: 5,
    step: 0.1,
  },
  toleranceTrajetMinutes: {
    source: 'qualite',
    kind: 'minutes',
    fields: ['toleranceTrajetMinutes'],
    min: 0,
    max: 120,
    step: 1,
  },
  toleranceArriveeGroupeeMinutes: {
    source: 'qualite',
    kind: 'minutes',
    fields: ['toleranceArriveeGroupeeMinutes'],
    min: 0,
    max: 240,
    step: 5,
  },
  heureServiceTardif: { source: 'qualite', kind: 'heure', fields: ['heureServiceTardif'] },
  heureServiceMatinal: { source: 'qualite', kind: 'heure', fields: ['heureServiceMatinal'] },
  reposSouhaiteApresServiceTardifMinutes: {
    source: 'qualite',
    kind: 'heures',
    fields: ['reposSouhaiteApresServiceTardifMinutes'],
    min: 0,
    step: 0.5,
  },
};

/** A record of settings as the page holds it: the stored one, or the changes typed over it. */
export type SettingsRecord = Readonly<Record<string, string | number | null | undefined>>;

/**
 * What a field shows: the typed value when there is one, the stored one
 * otherwise, converted for the eye — hours for a `heures` field, `HH:mm` for a
 * time the server sends as `HH:mm:ss`.
 */
export function shownValue(
  descriptor: SeuilDescriptor,
  field: string,
  stored: SettingsRecord | null,
  draft: SettingsRecord,
): string | number | null {
  const value = field in draft ? draft[field] : stored?.[field];
  if (value === undefined || value === null || value === '') {
    return descriptor.kind === 'heure' || descriptor.kind === 'plage' ? '' : null;
  }
  if (descriptor.kind === 'heures') {
    return Number(value) / 60;
  }
  if (descriptor.kind === 'heure' || descriptor.kind === 'plage') {
    return String(value).slice(0, 5);
  }
  return Number(value);
}

/**
 * The value to store for what was typed: minutes for hours, a number for a
 * number field, the time as typed — and `null` for an emptied field, which
 * the server reads as « no rule » on a time and refuses on a number.
 */
export function storedValue(descriptor: SeuilDescriptor, typed: string): string | number | null {
  const trimmed = typed.trim();
  if (descriptor.kind === 'heure' || descriptor.kind === 'plage') {
    return trimmed === '' ? null : trimmed;
  }
  if (trimmed === '') {
    return null;
  }
  const number = Number(trimmed.replace(',', '.'));
  if (!Number.isFinite(number)) {
    return null;
  }
  if (descriptor.kind === 'heures') {
    return Math.round(number * 60);
  }
  return descriptor.kind === 'decimal' ? number : Math.round(number);
}

/** True when the typed changes differ from what is stored — a value typed back to itself is no change. */
export function hasChanges(stored: SettingsRecord | null, draft: SettingsRecord): boolean {
  return Object.entries(draft).some(([field, value]) => !sameValue(stored?.[field], value));
}

function sameValue(
  a: string | number | null | undefined,
  b: string | number | null | undefined,
): boolean {
  // `HH:mm:ss` from the server, `HH:mm` from a time input: the same time.
  const normalise = (value: string | number | null | undefined): string => {
    const text = value === null || value === undefined ? '' : String(value);
    return /^\d{2}:\d{2}:\d{2}$/.test(text) ? text.slice(0, 5) : text;
  };
  return normalise(a) === normalise(b);
}

/**
 * The record to send: what is stored, with the typed changes over it. The
 * server replaces the whole record on a write, so a save built from the
 * changes alone would reset every field this tab did not touch.
 */
export function mergeSettings<T extends ParametresLegaux | ParametresQualite>(
  stored: T,
  draft: SettingsRecord,
): T {
  return { ...stored, ...draft } as T;
}
