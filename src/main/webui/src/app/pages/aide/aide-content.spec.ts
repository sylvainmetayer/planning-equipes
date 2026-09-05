import { describe, expect, it } from 'vitest';
import { Routes } from '@angular/router';
import { HelpSection, buildHelpSections, filterHelpSections } from './aide-content';
import { buildRaccourcisNavigation } from '../../core/keyboard-shortcuts';
import { routes } from '../../app.routes';

/**
 * Every path the router declares *and renders a page for*, children flattened
 * onto their parent. Pure redirections (`/decoupage`, `/validateur-yaml`, the
 * `**` catch-all…) are left out on purpose: the guide names several of those
 * screens, and a link landing on a redirect sends the reader somewhere other
 * than the screen it just named — which is exactly what this list must catch.
 */
function declaredPaths(table: Routes, prefix = ''): string[] {
  return table.flatMap((route) => {
    const path = [prefix, route.path ?? ''].filter(Boolean).join('/');
    const rendered = route.loadComponent !== undefined || route.component !== undefined;
    return [...(rendered ? [path] : []), ...declaredPaths(route.children ?? [], path)];
  });
}

/** Every string of a section, so the tests can assert on its whole content. */
function textOf(section: HelpSection): string {
  return JSON.stringify(section);
}

describe('buildHelpSections', () => {
  const sections = buildHelpSections();

  it('gives every section a unique anchor id', () => {
    const ids = sections.map((section) => section.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('gives every link exactly one destination: an in-app route or an external href', () => {
    const links = sections.flatMap((section) => section.links);
    expect(links.length).toBeGreaterThan(0);
    for (const link of links) {
      if (link.route !== undefined) {
        expect(link.href).toBeUndefined();
        expect(link.route.startsWith('/')).toBe(true);
      } else {
        expect(link.href).toMatch(/^(https:|mailto:)/);
      }
    }
  });

  it('only sends the reader to screens the router actually declares', () => {
    const declared = new Set(declaredPaths(routes));
    for (const link of sections.flatMap((section) => section.links)) {
      if (link.route !== undefined) {
        expect(declared).toContain(link.route.slice(1));
      }
    }
  });

  it('walks the whole cycle in getting started, not just up to the export', () => {
    const section = sections.find((candidate) => candidate.id === 'prise-en-main');
    expect(section).toBeDefined();
    const texte = textOf(section as HelpSection);
    // The two windows an organiser opens and closes by hand: they are the only
    // places an animateur writes anything, and they were missing here.
    expect(texte).toContain('Ouvrir la collecte des disponibilités');
    expect(texte).toContain('fermer la collecte');
    // The fair is open by default (`V42__foire_ouverture.sql`), so the step is
    // a check, not an action: a reader must not go looking for a switch to flip.
    expect(texte).toContain('foire au planning est ouverte');
    expect(texte).not.toContain('10. Ouvrir la foire');
    // The cycle does not end at the export: it ends at a schedule people have
    // received and acknowledged.
    expect(texte).toContain('Publier');
    expect(texte).toContain('accusés de réception');
    // Entering the staff is the very first thing the cycle does, and the CSV
    // import is one of the two ways to do it: naming it only in its own
    // section would hide it from the one reader who has not started yet.
    expect(texte).toContain('import CSV');
    // Nothing on a reference screen refuses in silence, and the cycle says so
    // where the reader is about to type for the first time.
    expect(texte).toContain('avertissement');
    // The « notify » box mails the espace link: it has nothing to send before
    // the records exist, so the order of the steps is part of the content.
    // `indexOf` returns -1 for a label that moved, which is below every real
    // index: without these two assertions the ordering one would pass on a
    // section that no longer holds either step.
    expect(texte).toContain('2. Saisir les référentiels');
    expect(texte).toContain('3. Ouvrir la collecte');
    expect(texte.indexOf('2. Saisir les référentiels')).toBeLessThan(
      texte.indexOf('3. Ouvrir la collecte')
    );
  });

  /**
   * The permanent subscription is offered from the espace, but the person who
   * gets asked "why is my calendar not updating?" is the organiser, and the
   * answer — publish — is only theirs to act on.
   */
  it('answers the calendar subscription from the admin side, not only in the espace help', () => {
    const section = sections.find((candidate) => candidate.id === 'foire-au-planning');
    const texte = textOf(section as HelpSection);
    expect(texte).toContain('Abonnement au calendrier');
    expect(texte).toContain('planning publié');
    // The two credentials are rotated separately server-side; saying otherwise
    // would send an organiser to regenerate the wrong one.
    expect(texte).toContain("ne coupe pas son abonnement");
    // And it is findable by the words somebody would actually type.
    const ids = filterHelpSections(sections, 'agenda').map((found) => found.id);
    expect(ids).toContain('foire-au-planning');
  });

  it('lists the CSV import where an organiser looks for imports', () => {
    const outils = sections.find((candidate) => candidate.id === 'echanges');
    expect(textOf(outils as HelpSection)).toContain('Import CSV des animateurs');
    const routes = (outils as HelpSection).links.map((link) => link.route);
    expect(routes).toContain('/import-animateurs');
  });

  /**
   * The import screen deep-links to `/aide#import-csv-animateurs`. A link that
   * lands on nothing is worse than no link, so the anchor is asserted here and
   * not only in the E2E — and the section has to be its own, not a paragraph
   * lost inside « Données de référence », which is where it started.
   */
  it('gives the CSV import an anchor of its own, reachable by that exact id', () => {
    const section = sections.find((candidate) => candidate.id === 'import-csv-animateurs');
    expect(section).toBeDefined();
    const texte = textOf(section as HelpSection);
    // What the screen's own help text claims, said again where the link lands.
    expect(texte).toContain('« | »');
    expect(texte).toContain('typologie:REFERENT');
    expect(texte).toContain('date de naissance');
    expect(texte).toContain("Télécharger un fichier d'exemple");
    // And nothing else still explains the import from inside another section.
    const donnees = sections.find((candidate) => candidate.id === 'donnees');
    expect(textOf(donnees as HelpSection)).not.toContain('CSV UTF-8');
    // The two words a lost user types into the search box of this very page.
    expect(filterHelpSections(sections, 'CSV')).toContainEqual(section);
    expect(filterHelpSections(sections, 'tableur')).toContainEqual(section);
  });

  it('covers the solver configuration and how to read a score', () => {
    const ids = sections.map((section) => section.id);
    expect(ids).toContain('configuration-solveur');
    expect(ids).toContain('lire-les-resultats');
    expect(ids).toContain('tuner');
  });

  it('documents the keyboard shortcuts, and can be found by looking for them', () => {
    const section = sections.find((candidate) => candidate.id === 'raccourcis-clavier');
    expect(section).toBeDefined();
    const texte = textOf(section as HelpSection);
    // The entry points, spelled the way a reader would look for them.
    for (const raccourci of ['Ctrl+K', 'Ctrl+Entrée', 'Échap']) {
      expect(texte).toContain(raccourci);
    }
    // The `g` + letter table itself, not just a mention that one exists.
    for (const raccourci of buildRaccourcisNavigation()) {
      expect(texte).toContain(`g ${raccourci.touche}`);
    }
    // Both words a lost user types into the search box of this very page.
    expect(filterHelpSections(sections, 'raccourci')).toContainEqual(section);
    expect(filterHelpSections(sections, 'clavier')).toContainEqual(section);
  });

  it('spells out the edge cases of the manual adjustments, not just what the four types are', () => {
    const section = sections.find((candidate) => candidate.id === 'ajustements-manuels');
    expect(section).toBeDefined();
    const texte = textOf(section as HelpSection);

    // The four types, each with what it actually covers.
    for (const type of ['Indisponibilité forcée', 'Affectation forcée', 'Incompatibilité', 'Affinité']) {
      expect(texte).toContain(type);
    }
    // The traps: any-one-of semantics, slot-not-stand, the empty scope, the
    // deleted slot, what is refused and what deliberately is not.
    expect(texte).toContain('jamais un « tous »');
    expect(texte).toContain('Elle porte sur le créneau, pas sur le stand');
    expect(texte).toContain('créneau supprimé');
    expect(texte).toContain('refusés à l\'enregistrement');
    expect(texte).toContain('Passent donc délibérément');
    expect(texte).toContain('réenregistrer sous son propre identifiant');
  });
});

describe('filterHelpSections', () => {
  const sections = buildHelpSections();

  it('returns everything for an empty or blank query', () => {
    expect(filterHelpSections(sections, '')).toHaveLength(sections.length);
    expect(filterHelpSections(sections, '   ')).toHaveLength(sections.length);
  });

  it('matches text hidden inside blocks, not just titles', () => {
    // "vacation" only ever appears in paragraph and definition bodies.
    const matches = filterHelpSections(sections, 'vacation');
    expect(matches.length).toBeGreaterThan(0);
    expect(matches.every((section) => textOf(section).includes('vacation'))).toBe(true);
    expect(matches.some((section) => !section.title.includes('vacation'))).toBe(true);
  });

  it('requires each term separately, not the whole phrase', () => {
    const matches = filterHelpSections(sections, 'score dur');
    expect(matches.length).toBeGreaterThan(0);
    expect(matches.every((section) => textOf(section).includes('score'))).toBe(true);
  });

  it('ignores case and diacritics', () => {
    expect(filterHelpSections(sections, 'DECOUPAGE')).toEqual(filterHelpSections(sections, 'découpage'));
  });

  it('requires every term of a multi-word query', () => {
    const both = filterHelpSections(sections, 'solveur zzzzz');
    expect(both).toHaveLength(0);
  });

  it('keeps getting started among the hits for « foire », which its last step names', () => {
    const ids = filterHelpSections(sections, 'foire').map((section) => section.id);
    expect(ids).toContain('foire-au-planning');
    expect(ids).toContain('prise-en-main');
    expect(ids).not.toContain('raccourcis-clavier');
  });

  it('returns nothing rather than everything when no section matches', () => {
    expect(filterHelpSections(sections, 'zzzzz')).toHaveLength(0);
  });

});

describe('parametrer-pour-un-planning-complet', () => {
  const sections = buildHelpSections();
  const section = sections.find((each) => each.id === 'parametrer-pour-un-planning-complet')!;

  it('sits between the solver configuration and the results', () => {
    const ids = sections.map((each) => each.id);
    expect(ids.indexOf('parametrer-pour-un-planning-complet')).toBe(ids.indexOf('configuration-solveur') + 1);
    expect(ids.indexOf('parametrer-pour-un-planning-complet')).toBe(ids.indexOf('lire-les-resultats') - 1);
  });

  it('names the three levers and the order to check them in', () => {
    const texte = section.blocks
      .flatMap((block) =>
        block.kind === 'paragraph' ? [block.text] : block.kind === 'list' ? block.items : block.items.map((d) => d.term + ' ' + d.text)
      )
      .join('\n');
    expect(texte).toContain('effectif par fenêtre');
    expect(texte).toContain('pause minimale entre vacations');
    expect(texte).toContain('Pause légale prise sur le poste');
    expect(texte).toContain('lu d\'un tenant');
    const etapes = section.blocks.find((block) => block.kind === 'list')!;
    expect(etapes.kind === 'list' && etapes.items.map((item) => item.slice(0, 2))).toEqual(['1.', '2.', '3.', '4.', '5.']);
    expect(etapes.kind === 'list' && etapes.items[0]).toContain('Besoin en animateurs');
    expect(etapes.kind === 'list' && etapes.items[4]).toContain('Pauses');
  });

  it('links every screen the guide names, and only real routes', () => {
    expect(section.links.map((link) => link.route)).toEqual([
      '/stands', '/ouvertures', '/creneaux', '/constraints', '/staffing', '/problemes', '/fragilite', '/pauses'
    ]);
  });

  it('is found by the words an organiser would type', () => {
    for (const mot of ['effectif par fenêtre', 'pause sur le poste', 'relève de midi', 'le moindre écart']) {
      expect(filterHelpSections(sections, mot).map((each) => each.id), mot).toContain('parametrer-pour-un-planning-complet');
    }
  });

  it('tells the stand and legal definitions about the new fields, without duplicating the guide', () => {
    const donnees = sections.find((each) => each.id === 'donnees')!;
    const stands = donnees.blocks.flatMap((b) => (b.kind === 'definitions' ? b.items : [])).find((d) => d.term === 'Stands')!;
    expect(stands.text).toContain('son propre effectif');
    const config = sections.find((each) => each.id === 'configuration-solveur')!;
    const legaux = config.blocks.flatMap((b) => (b.kind === 'definitions' ? b.items : [])).find((d) => d.term === 'Paramètres légaux')!;
    expect(legaux.text).toContain('pause légale prise sur le poste');
    expect(legaux.text).toContain('pause minimale entre deux vacations');
    // The measured figure lives in the guide only.
    expect(legaux.text).not.toContain('quarante-quatre');
  });
});
