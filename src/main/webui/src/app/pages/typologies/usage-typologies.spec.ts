import { describe, expect, it } from 'vitest';
import { Animateur, NiveauCompetence, Stand, TypologieItem } from '../../core/models';
import {
  EtatTypologie,
  etatsQueryParam,
  readEtatsParam,
  UsageTypologie,
  usagesTypologies,
} from './usage-typologies';
import casPartages from './usage-typologies.cas.json';

interface CasPartage {
  nom: string;
  typologies: { id: string; ninja?: boolean }[];
  stands: { id: string; typologies: string[] }[];
  animateurs: { id: string; competences: Record<string, string>; souhaits?: string[] }[];
  attendu: Record<string, Partial<Omit<UsageTypologie, 'typologieId' | 'etat'>> & { etat: string }>;
}

describe('usagesTypologies — cas partagés avec CoherenceAnalyzerTypologieUsageTest', () => {
  const cases = (casPartages as unknown as { cas: CasPartage[] }).cas;

  it('lit un jeu de cas non vide', () => {
    expect(cases.length).toBeGreaterThanOrEqual(10);
  });

  for (const cas of cases) {
    it(cas.nom, () => {
      const typologies: TypologieItem[] = cas.typologies.map((typologie) => ({
        id: typologie.id,
        label: typologie.id,
        ninja: typologie.ninja ?? false,
      }));
      const stands = cas.stands.map(
        (stand) =>
          ({ id: stand.id, nom: stand.id, typologiesProposees: stand.typologies }) as Stand,
      );
      const animateurs = cas.animateurs.map(
        (animateur) =>
          ({
            id: animateur.id,
            prenom: 'Prénom',
            nom: 'Nom',
            dateNaissance: '1990-01-01',
            manager: false,
            competences: animateur.competences as Record<string, NiveauCompetence>,
            souhaits: animateur.souhaits ?? [],
            joursIndisponibles: [],
          }) as Animateur,
      );

      const usages = usagesTypologies(typologies, stands, animateurs);

      expect(usages).toHaveLength(Object.keys(cas.attendu).length);
      for (const usage of usages) {
        const attendu = cas.attendu[usage.typologieId];
        expect(usage).toEqual({
          typologieId: usage.typologieId,
          competents: attendu.competents ?? 0,
          referents: attendu.referents ?? 0,
          autonomes: attendu.autonomes ?? 0,
          debutants: attendu.debutants ?? 0,
          polyvalents: attendu.polyvalents ?? 0,
          souhaits: attendu.souhaits ?? 0,
          stands: attendu.stands ?? 0,
          etat: attendu.etat as EtatTypologie,
        });
      }
    });
  }
});

describe('paramètre etat', () => {
  it('lit une liste séparée par des virgules et ignore ce qu’il ne connaît pas', () => {
    expect(readEtatsParam('orpheline,fragile')).toEqual(['ORPHELINE', 'FRAGILE']);
    expect(readEtatsParam('fragile, ORPHELINE,inconnu')).toEqual(['ORPHELINE', 'FRAGILE']);
    expect(readEtatsParam('sans-competent-inutilisee')).toEqual(['SANS_COMPETENT_INUTILISEE']);
    expect(readEtatsParam(null)).toEqual([]);
    expect(readEtatsParam('')).toEqual([]);
  });

  it('écrit l’inverse, et rien sans filtre', () => {
    expect(etatsQueryParam(['ORPHELINE', 'FRAGILE'])).toBe('orpheline,fragile');
    expect(etatsQueryParam([])).toBeNull();
  });
});
