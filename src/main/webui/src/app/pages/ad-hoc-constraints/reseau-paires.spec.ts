import { describe, expect, it } from 'vitest';
import { Animateur, ContrainteAdHoc, TypeContrainteAdHoc } from '../../core/models';
import { buildNetwork, disposer, resumeReseau, SEUIL_COLONNES } from './reseau-paires';

function animateur(id: string, prenom: string): Animateur {
  return { id, prenom, nom: 'Test' } as Animateur;
}

function pair(
  id: string,
  type: TypeContrainteAdHoc,
  ids: string[],
  portee: Partial<Pick<ContrainteAdHoc, 'creneau' | 'stand'>> = {},
): ContrainteAdHoc {
  return {
    id,
    type,
    animateursConcernes: ids.map((each) => ({ id: each })),
    creneau: null,
    stand: null,
    raison: `raison ${id}`,
    ...portee,
  };
}

const ANIMATEURS = ['A', 'B', 'C', 'D', 'E', 'F'].map((id) => animateur(id, id));

describe('buildNetwork', () => {
  it('draws one edge per pair, whatever the order the two names were given in', () => {
    const reseau = buildNetwork(
      [pair('X1', 'AFFINITE', ['A', 'B']), pair('X2', 'AFFINITE', ['B', 'A'])],
      ANIMATEURS,
    );

    expect(reseau.aretes).toHaveLength(1);
    expect(reseau.aretes[0]).toMatchObject({ source: 'A', target: 'B', type: 'AFFINITE' });
    expect(reseau.aretes[0].contraintes.map((contrainte) => contrainte.id)).toEqual(['X1', 'X2']);
  });

  it('draws only the two relating types, and only their first two animateurs', () => {
    const reseau = buildNetwork(
      [
        pair('X1', 'INCOMPATIBILITE', ['A', 'B', 'C']),
        pair('X2', 'AFFECTATION_FORCEE', ['A', 'D']),
        pair('X3', 'INDISPONIBILITE_FORCEE', ['E', 'F']),
      ],
      ANIMATEURS,
    );

    expect(reseau.aretes.map((arete) => arete.key)).toEqual(['INCOMPATIBILITE:A|B']);
    expect(reseau.noeuds.map((noeud) => noeud.id)).toEqual(['A', 'B']);
  });

  it('ignores a malformed adjustment — fewer than two people, or the same one twice', () => {
    const reseau = buildNetwork(
      [pair('X1', 'AFFINITE', ['A']), pair('X2', 'AFFINITE', ['A', 'A'])],
      ANIMATEURS,
    );
    expect(reseau.aretes).toEqual([]);
    expect(reseau.noeuds).toEqual([]);
    expect(reseau.sansPaire).toBe(6);
  });

  it('marks an edge narrowed to a timeslot or a stand', () => {
    const reseau = buildNetwork(
      [
        pair('X1', 'AFFINITE', ['A', 'B'], { creneau: { id: 3 } }),
        pair('X2', 'INCOMPATIBILITE', ['C', 'D'], { stand: { id: 'S1' } }),
        pair('X3', 'INCOMPATIBILITE', ['E', 'F']),
      ],
      ANIMATEURS,
    );
    expect(reseau.aretes.map((arete) => [arete.key, arete.restreinte])).toEqual([
      ['AFFINITE:A|B', true],
      ['INCOMPATIBILITE:C|D', true],
      ['INCOMPATIBILITE:E|F', false],
    ]);
  });

  it('does not mark an edge whose adjustment omits its timeslot and stand rather than sending null', () => {
    const withoutFields = { ...pair('X1', 'AFFINITE', ['A', 'B']) } as Partial<ContrainteAdHoc>;
    delete withoutFields.creneau;
    delete withoutFields.stand;
    const reseau = buildNetwork(
      [
        withoutFields as ContrainteAdHoc,
        pair('X2', 'INCOMPATIBILITE', ['C', 'D'], { stand: undefined, creneau: { id: 3 } }),
      ],
      ANIMATEURS,
    );
    expect(reseau.aretes.map((arete) => [arete.key, arete.restreinte])).toEqual([
      ['AFFINITE:A|B', false],
      ['INCOMPATIBILITE:C|D', true],
    ]);
  });

  it('orders by label in French, accents included, whatever the default locale', () => {
    const accents = [animateur('E1', 'Émile'), animateur('E2', 'Eric'), animateur('E3', 'Zoé')];
    const reseau = buildNetwork(
      [pair('X1', 'AFFINITE', ['E3', 'E2']), pair('X2', 'AFFINITE', ['E2', 'E1'])],
      accents,
    );
    expect(reseau.grappes[0].membres).toEqual(['E1', 'E2', 'E3']);
  });

  it('keeps an animateur no longer in the referential as an « unknown » node, never an exception', () => {
    const reseau = buildNetwork([pair('X1', 'AFFINITE', ['A', 'ZZ'])], ANIMATEURS);
    const inconnu = reseau.noeuds.find((noeud) => noeud.id === 'ZZ');
    expect(inconnu?.inconnu).toBe(true);
    expect(inconnu?.label).toContain('animateur inconnu');
    expect(reseau.grappes[0].membres).toContain('ZZ');
  });

  it('groups the affinity components into clusters, the largest first, and sets the incompatibility-only people apart', () => {
    const reseau = buildNetwork(
      [
        pair('X1', 'AFFINITE', ['E', 'F']),
        pair('X2', 'AFFINITE', ['A', 'B']),
        pair('X3', 'AFFINITE', ['B', 'C']),
        pair('X4', 'INCOMPATIBILITE', ['A', 'D']),
      ],
      [...ANIMATEURS, animateur('G', 'G')],
    );

    expect(reseau.grappes).toEqual([
      { numero: 1, membres: ['A', 'B', 'C'] },
      { numero: 2, membres: ['E', 'F'] },
    ]);
    expect(reseau.isoles).toEqual(['D']);
    expect(reseau.sansPaire).toBe(1);
    expect(reseau.noeuds.find((noeud) => noeud.id === 'A')).toMatchObject({
      affinites: 1,
      incompatibilites: 1,
      grappe: 1,
    });
  });

  it('detects an incompatibility inside an affinity cluster (A~B, B~C, A≠C)', () => {
    const reseau = buildNetwork(
      [
        pair('X1', 'AFFINITE', ['A', 'B']),
        pair('X2', 'AFFINITE', ['B', 'C']),
        pair('X3', 'INCOMPATIBILITE', ['C', 'A']),
        pair('X4', 'INCOMPATIBILITE', ['A', 'D']),
      ],
      ANIMATEURS,
    );

    expect(reseau.incompatibilitesInternes.map((arete) => arete.key)).toEqual([
      'INCOMPATIBILITE:A|C',
    ]);
    expect(resumeReseau(reseau)).toBe(
      '1 grappe(s), 4 personne(s), 1 incompatibilité(s) interne(s)',
    );
  });

  it('flags a pair declared both affine and incompatible, as older data may hold', () => {
    const reseau = buildNetwork(
      [pair('X1', 'AFFINITE', ['A', 'B']), pair('X2', 'INCOMPATIBILITE', ['B', 'A'])],
      ANIMATEURS,
    );
    expect(reseau.aretes).toHaveLength(2);
    expect(reseau.aretes.every((arete) => arete.doublee)).toBe(true);
    // The affinity puts both in one cluster, yet the doubled pair is counted once, as doubled.
    expect(reseau.aretes.some((arete) => arete.interne)).toBe(false);
    expect(reseau.incompatibilitesInternes).toHaveLength(0);
  });
});

describe('disposer', () => {
  const contraintes = [
    pair('X1', 'AFFINITE', ['A', 'B']),
    pair('X2', 'AFFINITE', ['B', 'C']),
    pair('X3', 'AFFINITE', ['E', 'F']),
    pair('X4', 'INCOMPATIBILITE', ['A', 'D']),
    pair('X5', 'INCOMPATIBILITE', ['C', 'E'], { stand: { id: 'S1' } }),
  ];

  it('gives the same drawing for the same data, whatever the order it arrives in', () => {
    const first = disposer(buildNetwork(contraintes, ANIMATEURS), 960);
    const autre = disposer(
      buildNetwork([...contraintes].reverse(), [...ANIMATEURS].reverse()),
      960,
    );

    expect(autre).toEqual(first);
    expect(first.cadres.map((cadre) => [cadre.grappe, cadre.taille])).toEqual([
      [1, 3],
      [2, 2],
      [null, 1],
    ]);
    expect(first.noeuds.size).toBe(6);
  });

  it('places every member of a cluster inside its frame', () => {
    const disposition = disposer(buildNetwork(contraintes, ANIMATEURS), 960);
    const cadre = disposition.cadres[0];
    for (const id of ['A', 'B', 'C']) {
      const position = disposition.noeuds.get(id);
      expect(position?.x).toBeGreaterThan(cadre.x);
      expect(position?.x).toBeLessThan(cadre.x + cadre.largeur);
      expect(position?.y).toBeGreaterThan(cadre.y);
      expect(position?.y).toBeLessThan(cadre.y + cadre.hauteur);
    }
  });

  it('draws inside a frame straight, and between frames as a curve', () => {
    const disposition = disposer(buildNetwork(contraintes, ANIMATEURS), 960);
    const trace = (key: string) => disposition.aretes.find((each) => each.key === key)?.d ?? '';
    expect(trace('AFFINITE:A|B')).toMatch(/^M [\d.]+ [\d.]+ L /);
    expect(trace('INCOMPATIBILITE:C|E')).toMatch(/ Q /);
  });

  it('wraps the frames onto a new row when they no longer fit the width', () => {
    const disposition = disposer(buildNetwork(contraintes, ANIMATEURS), 300);
    expect(new Set(disposition.cadres.map((cadre) => cadre.y)).size).toBeGreaterThan(1);
    expect(disposition.hauteur).toBeGreaterThan(disposition.cadres[1].y);
  });

  it('switches a cluster past the threshold to columns', () => {
    const nombreux = Array.from({ length: SEUIL_COLONNES + 2 }, (_, index) =>
      animateur(`P${String(index).padStart(2, '0')}`, `P${index}`),
    );
    const chaine = nombreux
      .slice(1)
      .map((each, index) => pair(`L${index}`, 'AFFINITE', [nombreux[index].id, each.id]));

    const disposition = disposer(buildNetwork(chaine, nombreux), 960);

    expect(disposition.cadres).toHaveLength(1);
    expect(disposition.cadres[0].enColonnes).toBe(true);
    const xs = new Set([...disposition.noeuds.values()].map((position) => position.x));
    expect(xs.size).toBe(3);
  });

  it('bows an edge between two members of one column around the members in between', () => {
    const nombreux = Array.from({ length: SEUIL_COLONNES + 2 }, (_, index) =>
      animateur(`P${String(index).padStart(2, '0')}`, `P${String(index).padStart(2, '0')}`),
    );
    const chaine = nombreux
      .slice(1)
      .map((each, index) => pair(`L${index}`, 'AFFINITE', [nombreux[index].id, each.id]));
    // P00 and P05 share the first column, five rows apart; P00 and P01 are neighbours.
    const eloignee = pair('Z1', 'INCOMPATIBILITE', ['P00', 'P05']);

    const disposition = disposer(buildNetwork([...chaine, eloignee], nombreux), 960);
    const trace = (key: string) => disposition.aretes.find((each) => each.key === key);

    const haut = disposition.noeuds.get('P00');
    const bas = disposition.noeuds.get('P05');
    expect(haut?.x).toBe(bas?.x);
    const arc = trace('INCOMPATIBILITE:P00|P05');
    expect(arc?.d).toMatch(/ Q /);
    // It passes left of the column, past the widest node, and stays in its frame.
    expect(arc?.milieuX).toBeLessThan((haut?.x ?? 0) - 15);
    expect(arc?.milieuX).toBeGreaterThan(disposition.cadres[0].x);
    // Neighbours in a column have nobody in between: still a straight line.
    expect(trace('AFFINITE:P00|P01')?.d).toMatch(/ L /);
  });

  it('draws nothing for an empty network', () => {
    const disposition = disposer(buildNetwork([], ANIMATEURS), 960);
    expect(disposition).toMatchObject({ largeur: 0, hauteur: 0, cadres: [], aretes: [] });
  });
});
