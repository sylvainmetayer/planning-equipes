/**
 * Content of the in-app user guide, kept as data rather than as template
 * markup: the page renders it generically, the filter box searches it, and a
 * unit test can assert on it without rendering anything.
 *
 * Every string is a translatable message, so the guide is bilingual like the
 * rest of the UI. See `docs/` for the technical documentation this summarises
 * — the guide answers "how do I use this screen", not "how is it built".
 *
 * The content itself lives under `content/`, one file per theme, in the order
 * the guide is read; this module assembles it and searches it. The blocks and
 * their renderer are the ones both guides share (`shared/help-blocks.ts`).
 */

import { HelpBlock, HelpDefinition, helpBlockText } from '../../shared/help-blocks';
import { HelpLink, HelpSection } from './help-section';
import { buildAnimateurSideSections } from './content/animateurs';
import { buildGettingStartedSections } from './content/getting-started';
import { buildOperationsSections } from './content/operations';
import { buildReferenceDataSections } from './content/reference-data';
import { buildSolverSections } from './content/solver';
import { buildToolsAndContactSections } from './content/tools-and-contact';

export type { HelpBlock, HelpDefinition, HelpLink, HelpSection };

/**
 * Built lazily (never at module scope): `$localize` only resolves once
 * `main.ts` has loaded the translation catalog, which happens after this
 * module is imported. Same reasoning as `buildNavGroups()` in `app.ts`.
 *
 * @param supportEmail the deployment's support address (BRANDING_SUPPORT_EMAIL);
 *                     with none, the contact section leaves the e-mail out.
 */
export function buildHelpSections(supportEmail = ''): HelpSection[] {
  return [
    ...buildGettingStartedSections(),
    ...buildReferenceDataSections(),
    ...buildSolverSections(),
    ...buildOperationsSections(),
    ...buildAnimateurSideSections(),
    ...buildToolsAndContactSections(supportEmail),
  ];
}

/** Lowercases and strips diacritics so "référent" is found by typing "referent". */
function normalize(value: string): string {
  return value
    .toLowerCase()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '');
}

/** Every searchable string of a section, flattened. */
function searchableText(section: HelpSection): string {
  const blockText = section.blocks.flatMap(helpBlockText);
  return [
    section.title,
    section.summary,
    ...blockText,
    ...section.links.map((link) => link.label),
  ].join(' ');
}

/**
 * Whole-section filter: a section either matches the query or is hidden. The
 * guide is read section by section, so hiding individual paragraphs inside a
 * section would strip the context that makes the matching sentence useful.
 */
export function filterHelpSections(sections: HelpSection[], query: string): HelpSection[] {
  const terms = normalize(query).split(/\s+/).filter(Boolean);
  if (terms.length === 0) {
    return sections;
  }
  return sections.filter((section) => {
    const haystack = normalize(searchableText(section));
    return terms.every((term) => haystack.includes(term));
  });
}
