import { describe, expect, it } from 'vitest';
import { Animateur, AnimateurBanc, BancDeTouche, Creneau, MotifExclusion } from '../../core/models';
import {
  creneauxUtiles,
  etatDe,
  familleUtile,
  libelleCreneau,
  libelleStand,
  lignes,
  ordreMotifs
} from './banc-de-touche';

const motif = (contrainte: string, niveau: MotifExclusion['niveau']): MotifExclusion => ({
  contrainte,
  niveau,
  categorie: 'Légal (temps de travail)',
  description: 'Une règle du Code du travail.'
});

const ligne = (partial: Partial<AnimateurBanc> & { animateurId: string }): AnimateurBanc => ({
  disponible: true,
  degradeLePlan: false,
  delta: null,
  motifs: [],
  ...partial
});

const animateur = (id: string, prenom: string, nom: string): Animateur => ({
  id,
  prenom,
  nom,
  dateNaissance: '1990-01-01',
  manager: false,
  competences: {},
  souhaits: [],
  joursIndisponibles: []
});

describe('etatDe', () => {
  it('sépare les trois états au lieu de les aplatir en deux', () => {
    expect(etatDe(ligne({ animateurId: 'A', disponible: true, degradeLePlan: false }))).toBe('disponible');
    expect(etatDe(ligne({ animateurId: 'B', disponible: false, degradeLePlan: false }))).toBe('sousReserve');
    expect(etatDe(ligne({ animateurId: 'C', disponible: false, degradeLePlan: true }))).toBe('impossible');
  });

  // The server guarantees `disponible` implies `!degradeLePlan`; if that ever
  // stops holding, the stricter reading must win rather than the laxer one.
  it("retient le verdict le plus strict si le serveur se contredit", () => {
    expect(etatDe(ligne({ animateurId: 'D', disponible: true, degradeLePlan: true }))).toBe('impossible');
  });
});

describe('ordreMotifs', () => {
  it('remonte les règles dures avant les pénalités, puis trie par nom', () => {
    const ordonnes = ordreMotifs([
      motif('souhaitsIncompatibles', 'MEDIUM'),
      motif('reposQuotidienMinimal', 'HARD'),
      motif('animateurDisponible', 'HARD')
    ]);

    expect(ordonnes.map((m) => m.contrainte)).toEqual([
      'animateurDisponible',
      'reposQuotidienMinimal',
      'souhaitsIncompatibles'
    ]);
  });

  it('ne modifie pas le tableau reçu', () => {
    const source = [motif('b', 'MEDIUM'), motif('a', 'HARD')];
    ordreMotifs(source);
    expect(source.map((m) => m.contrainte)).toEqual(['b', 'a']);
  });
});

describe('lignes', () => {
  const banc: BancDeTouche = {
    creneauId: 1,
    statut: 'EVALUATED',
    posteCibleId: 'P1',
    standCibleId: 'S1',
    animateurCibleId: null,
    total: 3,
    disponibles: 1,
    creneauxAvecSieges: [1, 2],
    animateurs: [
      ligne({ animateurId: 'A1' }),
      ligne({
        animateurId: 'A2',
        disponible: false,
        degradeLePlan: true,
        delta: { hardScore: -3, mediumScore: 0, softScore: 0 },
        motifs: [motif('souhaitsIncompatibles', 'MEDIUM'), motif('animateurDisponible', 'HARD')]
      }),
      ligne({ animateurId: 'A-INCONNU', disponible: false, degradeLePlan: false })
    ]
  };

  it('garde l’ordre du serveur et nomme chaque animateur', () => {
    const rows = lignes(banc, [animateur('A1', 'Léa', 'Martin'), animateur('A2', 'Omar', 'Bernard')]);

    expect(rows.map((row) => row.nom)).toEqual(['Léa Martin', 'Omar Bernard', 'A-INCONNU']);
    expect(rows.map((row) => row.etat)).toEqual(['disponible', 'impossible', 'sousReserve']);
  });

  it('remonte le coût dur et ordonne les motifs', () => {
    const rows = lignes(banc, [animateur('A2', 'Omar', 'Bernard')]);
    const omar = rows.find((row) => row.animateurId === 'A2');

    expect(omar?.coutDur).toBe(-3);
    expect(omar?.motifs.map((m) => m.contrainte)).toEqual(['animateurDisponible', 'souhaitsIncompatibles']);
  });

  // The bench is read from a persisted plan, which can legitimately be older
  // than a since-deleted animateur. Dropping the row would silently shorten a
  // list whose whole point is to be exhaustive.
  it("garde une ligne dont l'animateur n'est plus au référentiel", () => {
    const rows = lignes(banc, []);
    expect(rows).toHaveLength(3);
    expect(rows[2].nom).toBe('A-INCONNU');
  });

  it('rend une liste vide sans réponse du serveur', () => {
    expect(lignes(null, [])).toEqual([]);
  });
});

describe('libellés', () => {
  const creneau: Creneau = {
    id: 7,
    jour: 3,
    date: '2026-07-16',
    heureDebut: '10:00',
    heureFin: '13:00'
  };

  it('distingue deux vacations de même horaire par leur famille', () => {
    expect(libelleCreneau({ ...creneau, famille: 1 }, true)).toBe('J3 · 2026-07-16 · 10:00-13:00 (F2)');
    expect(libelleCreneau({ ...creneau, famille: 0 }, true)).toBe('J3 · 2026-07-16 · 10:00-13:00 (F1)');
  });

  // Every créneau carries a family; tagging them all « F1 » when no découpage
  // ran is noise on top of the one thing the label exists for.
  it('ne mentionne la famille que lorsqu’un découpage en a produit plusieurs', () => {
    expect(libelleCreneau(creneau)).toBe('J3 · 2026-07-16 · 10:00-13:00');
    expect(familleUtile([creneau, { ...creneau, id: 8, famille: 0 }])).toBe(false);
    expect(familleUtile([creneau, { ...creneau, id: 8, famille: 1 }])).toBe(true);
  });

  it('coupe les secondes que l’API renvoie sur les horaires', () => {
    expect(libelleCreneau({ ...creneau, heureDebut: '10:00:00', heureFin: '13:00:00' })).toBe(
      'J3 · 2026-07-16 · 10:00-13:00'
    );
  });

  it("retombe sur l'id d'un stand absent du référentiel, et rend vide sans stand", () => {
    const stands = [{ id: 'S1', nom: 'Chamboule-tout' }];
    expect(libelleStand(stands as never, 'S1')).toBe('Chamboule-tout');
    expect(libelleStand(stands as never, 'S9')).toBe('S9');
    expect(libelleStand(stands as never, null)).toBe('');
  });
});

// The regression the screen shipped with: its selector lists the referential's
// timeslots, the backend only knows the ones the saved plan holds a seat on,
// and the mismatch used to surface as « Créneau inconnu » on first render.
describe('creneauxUtiles', () => {
  const vide: BancDeTouche = {
    creneauId: 9,
    statut: 'NO_SEAT',
    posteCibleId: null,
    standCibleId: null,
    animateurCibleId: null,
    total: 0,
    disponibles: 0,
    creneauxAvecSieges: [1, 2],
    animateurs: []
  };

  it('désigne les créneaux que le plan enregistré porte réellement', () => {
    const utiles = creneauxUtiles(vide);
    expect(utiles.has(1)).toBe(true);
    expect(utiles.has(9)).toBe(false);
  });

  // Marking every option would be noise, not guidance.
  it('ne marque rien quand la réponse ne porte aucune information', () => {
    expect(creneauxUtiles(null).size).toBe(0);
    expect(creneauxUtiles({ ...vide, statut: 'NO_PLAN', creneauxAvecSieges: [] }).size).toBe(0);
  });

  it("rend une liste vide de lignes sur une réponse sans siège, sans planter", () => {
    expect(lignes(vide, [])).toEqual([]);
  });
});
