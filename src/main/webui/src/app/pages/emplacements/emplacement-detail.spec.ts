import { describe, expect, it } from 'vitest';
import { Emplacement, Stand } from '../../core/models';
import { buildEmplacementDetail } from './emplacement-detail';

function stand(id: string, emplacement: Emplacement | null): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

const place: Emplacement = {
  id: 'PLACE',
  nom: 'Place du Drapeau',
  latitude: 46.6487,
  longitude: 2.2503,
};

describe('buildEmplacementDetail', () => {
  it('lists the stands tied to this place, and only those', () => {
    const autre: Emplacement = { id: 'AUTRE', nom: 'Ailleurs', latitude: null, longitude: null };
    const sections = buildEmplacementDetail(place, [
      stand('S1', place),
      stand('S2', autre),
      stand('S3', place),
    ]);

    expect(sections.flatMap((section) => section.rows).find((row) => row.chips)?.chips).toEqual([
      'S1',
      'S3',
    ]);
  });

  it('warns that a place without coordinates does not constrain travel', () => {
    const sections = buildEmplacementDetail({
      id: 'X',
      nom: 'Sans GPS',
      latitude: null,
      longitude: null,
    });

    const values = sections.flatMap((section) => section.rows).map((row) => row.value);
    expect(values).toContain(
      "Sans coordonnées, la règle sur les déplacements lointains ne s'applique pas à ces stands",
    );
  });

  it('does not add that warning for a geocoded place', () => {
    const sections = buildEmplacementDetail(place, []);

    const labels = sections.flatMap((section) => section.rows).map((row) => row.label);
    expect(labels).not.toContain('Effet sur le planning');
  });
});
