import { describe, expect, it } from 'vitest';
import { EntreeHistorique } from '../../core/models';
import {
  entitesPresentes,
  exportCodes,
  filter,
  journeeLocale,
  readActorFilter,
  readNatureFilter,
  readOutcomeFilter,
  natureQuery,
  parJournee,
  qui,
  surQuoi,
} from './historique';

function entree(partial: Partial<EntreeHistorique> = {}): EntreeHistorique {
  return {
    id: 1,
    survenuLe: '2026-09-07T14:32:00Z',
    acteur: 'ADMIN',
    acteurId: 'admin',
    acteurNom: null,
    action: 'ANIMATEUR_MODIFIE',
    libelle: 'Fiche animateur modifiée',
    entite: 'ANIMATEUR',
    entiteId: 'A1',
    entiteNom: 'Alice Martin',
    champs: ['nom', 'email'],
    resultat: 'SUCCES',
    statut: 200,
    ...partial,
  };
}

describe('filter', () => {
  it('garde tout par défaut', () => {
    const entrees = [entree(), entree({ id: 2, acteur: 'ANIMATEUR' })];
    expect(filter(entrees, 'TOUS', 'TOUS', '', '')).toHaveLength(2);
  });

  it('filtre par auteur, par résultat et par objet', () => {
    const entrees = [
      entree(),
      entree({ id: 2, acteur: 'ASSISTANT', acteurId: 'mcp' }),
      entree({ id: 3, resultat: 'REFUS', statut: 409 }),
      entree({ id: 4, entite: 'STAND', entiteId: 'S1', entiteNom: null }),
    ];
    expect(filter(entrees, 'ASSISTANT', 'TOUS', '', '').map((e) => e.id)).toEqual([2]);
    expect(filter(entrees, 'TOUS', 'REFUS', '', '').map((e) => e.id)).toEqual([3]);
    expect(filter(entrees, 'TOUS', 'TOUS', 'STAND', '').map((e) => e.id)).toEqual([4]);
  });

  /** The search covers what the row shows — and only that, or it would look broken. */
  it('cherche dans la phrase, la personne, l’objet et les champs', () => {
    const entrees = [
      entree(),
      entree({
        id: 2,
        libelle: 'Stand ajouté',
        entite: 'STAND',
        entiteId: 'S1',
        entiteNom: null,
        champs: [],
      }),
    ];
    expect(filter(entrees, 'TOUS', 'TOUS', '', 'alice').map((e) => e.id)).toEqual([1]);
    expect(filter(entrees, 'TOUS', 'TOUS', '', 'email').map((e) => e.id)).toEqual([1]);
    expect(filter(entrees, 'TOUS', 'TOUS', '', 'stand').map((e) => e.id)).toEqual([2]);
    // Accent- and case-insensitive, like every other quick filter here.
    expect(filter(entrees, 'TOUS', 'TOUS', '', 'MODIFIEE').map((e) => e.id)).toEqual([1]);
  });
});

describe('qui', () => {
  it('nomme l’animateur encore au référentiel, et son identifiant sinon', () => {
    expect(qui(entree({ acteur: 'ANIMATEUR', acteurId: 'A1', acteurNom: 'Alice Martin' }))).toBe(
      'Alice Martin',
    );
    expect(qui(entree({ acteur: 'ANIMATEUR', acteurId: 'A1', acteurNom: null }))).toBe('A1');
  });

  it('nomme les trois autres auteurs sans inventer d’identité', () => {
    expect(qui(entree({ acteur: 'SYSTEME', acteurId: null }))).toBe('Application');
    expect(qui(entree({ acteur: 'ASSISTANT', acteurId: 'mcp' }))).toBe('Assistant (MCP)');
    expect(qui(entree({ acteur: 'ADMIN', acteurId: 'admin' }))).toBe('admin');
  });
});

describe('surQuoi', () => {
  it('montre le nom quand il est connu, l’identifiant seul sinon', () => {
    expect(surQuoi(entree())).toBe('Alice Martin (A1)');
    expect(surQuoi(entree({ entiteNom: null }))).toBe('A1');
    expect(surQuoi(entree({ entiteId: null, entiteNom: null }))).toBe('');
  });
});

describe('parJournee', () => {
  it('regroupe par jour civil dans l’ordre reçu', () => {
    // Les jours attendus se dérivent de la même horloge que le code : écrits
    // en dur, ce test tomberait sur une machine réglée sur un autre fuseau.
    const midi = '2026-09-07T12:00:00Z';
    const matin = '2026-09-07T09:05:00Z';
    const veille = '2026-09-06T12:00:00Z';
    const journees = parJournee([
      entree({ id: 1, survenuLe: midi }),
      entree({ id: 2, survenuLe: matin }),
      entree({ id: 3, survenuLe: veille }),
    ]);
    expect(journees.map((j) => j.jour)).toEqual([journeeLocale(midi), journeeLocale(veille)]);
    expect(journees[0].entrees.map((e) => e.id)).toEqual([1, 2]);
  });

  /**
   * Le jour est celui du lecteur, pas celui d'UTC : l'heure affichée à côté
   * est locale, donc une action de 22h30 UTC un 7 septembre s'affiche « 00:30 »
   * à Paris et appartient au 8. La regrouper sous le 7 mettrait minuit avant
   * le soir de la veille — et c'est le travail de nuit qu'on vient relire.
   */
  it('range une action du soir sous la journée du lecteur, pas sous celle d’UTC', () => {
    const veille = journeeLocale('2026-09-07T22:30:00Z');
    const attendu = new Date('2026-09-07T22:30:00Z');
    const mois = `${attendu.getMonth() + 1}`.padStart(2, '0');
    expect(veille).toBe(
      `${attendu.getFullYear()}-${mois}-${`${attendu.getDate()}`.padStart(2, '0')}`,
    );

    const journees = parJournee([
      entree({ id: 1, survenuLe: '2026-09-07T22:30:00Z' }),
      entree({ id: 2, survenuLe: '2026-09-07T08:00:00Z' }),
    ]);
    // Sur un fuseau à l'est de Greenwich les deux tombent des jours
    // différents ; sur UTC elles tombent le même. Dans les deux cas le
    // regroupement suit l'horloge du lecteur, ce que l'ancien `slice(0, 10)`
    // ne faisait pas.
    const memeJour =
      journeeLocale('2026-09-07T22:30:00Z') === journeeLocale('2026-09-07T08:00:00Z');
    expect(journees).toHaveLength(memeJour ? 1 : 2);
  });
});

describe('lecture des filtres depuis l’URL', () => {
  it('retombe sur le défaut pour une valeur inconnue', () => {
    expect(readActorFilter('ASSISTANT')).toBe('ASSISTANT');
    expect(readActorFilter('inventé')).toBe('TOUS');
    expect(readActorFilter(null)).toBe('TOUS');
    expect(readOutcomeFilter('REFUS')).toBe('REFUS');
    expect(readOutcomeFilter('peut-être')).toBe('TOUS');
    expect(readNatureFilter('EXPORTS')).toBe('EXPORTS');
    expect(readNatureFilter('exports')).toBe('TOUTES');
    expect(readNatureFilter(null)).toBe('TOUTES');
  });
});

describe('the Exports filter', () => {
  it('is a question put to the server, never a filter over the loaded page', () => {
    expect(natureQuery('EXPORTS')).toBe('exports');
    expect(natureQuery('TOUTES')).toBeNull();
  });

  it('takes the classification from the server’s catalogue, not from the code', () => {
    const codes = exportCodes([
      {
        code: 'EXPORT_REFERENTIELS',
        libelle: 'Référentiels exportés en CSV',
        entite: 'PLANNING',
        export: true,
      },
      {
        code: 'TELECHARGEMENT_ESPACE_PDF',
        libelle: 'Planning téléchargé',
        entite: 'ANIMATEUR',
        export: true,
      },
      // A spelling that looks like an export is not one unless the catalogue says so.
      { code: 'EXPORT_FICTIF', libelle: 'Pas un export', entite: null, export: false },
      {
        code: 'ANIMATEUR_MODIFIE',
        libelle: 'Fiche animateur modifiée',
        entite: 'ANIMATEUR',
        export: false,
      },
    ]);
    expect([...codes].sort()).toEqual(['EXPORT_REFERENTIELS', 'TELECHARGEMENT_ESPACE_PDF']);
  });

  it('leaves the other filters to combine over what the server sent', () => {
    const entrees = [
      entree({ id: 2, action: 'EXPORT_REFERENTIELS', entite: 'PLANNING', champs: ['stands'] }),
      entree({
        id: 3,
        action: 'TELECHARGEMENT_ESPACE_PDF',
        acteur: 'ANIMATEUR',
        acteurId: 'A1',
        champs: [],
      }),
    ];
    expect(filter(entrees, 'ANIMATEUR', 'TOUS', '', '').map((e) => e.id)).toEqual([3]);
    expect(filter(entrees, 'TOUS', 'TOUS', '', 'stands').map((e) => e.id)).toEqual([2]);
  });
});

describe('entitesPresentes', () => {
  it('ne propose que les objets réellement présents, triés', () => {
    const entrees = [
      entree({ entite: 'STAND' }),
      entree({ id: 2 }),
      entree({ id: 3, entite: null }),
    ];
    expect(entitesPresentes(entrees)).toEqual(['ANIMATEUR', 'STAND']);
  });
});
