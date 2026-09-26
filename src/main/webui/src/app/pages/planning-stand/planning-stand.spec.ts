import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, PosteAffectation, Stand } from '../../core/models';
import { planningDays } from '../journee/journee';
import { joursGrille } from '../planning-grille/jours-grille';
import { buildTableauStands, readDensiteStand, readStandView } from './planning-stand';

function stand(id: string, emplacement: string | null = null): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 2,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: emplacement
      ? { id: emplacement, nom: emplacement, latitude: null, longitude: null }
      : null,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function personne(id: string, prenom: string, nom: string): Animateur {
  return {
    id,
    prenom,
    nom,
    dateNaissance: '1990-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
  };
}

const J1: Creneau = { id: 1, jour: 1, date: '2026-09-05', heureDebut: '09:00', heureFin: '12:00' };
const J2: Creneau = { id: 2, jour: 2, date: '2026-09-06', heureDebut: '09:00', heureFin: '12:00' };
const TIR = stand('Tir', 'Halle');
const DIXIT = stand('Dixit');
const ALICE = personne('a', 'Alice', 'Martin');
const BRUNO = personne('b', 'Bruno', 'Petit');

function siege(id: string, sur: Stand, creneau: Creneau, qui: Animateur | null): PosteAffectation {
  return { id, stand: sur, creneau, animateur: qui };
}

const POSTES = [
  siege('p1', TIR, J1, ALICE),
  siege('p2', TIR, J1, null),
  siege('p3', TIR, J2, BRUNO),
  siege('p4', DIXIT, J2, null),
];

function tableau(filtres: Partial<Parameters<typeof buildTableauStands>[3]> = {}) {
  const jours = planningDays(POSTES);
  return buildTableauStands(POSTES, jours, joursGrille(jours), {
    standsRetenus: null,
    animateur: '',
    recherche: '',
    ...filtres,
  });
}

describe('Par stand', () => {
  it('reads its view and its density tolerantly', () => {
    expect(readDensiteStand('compteurs')).toBe('compteurs');
    expect(readDensiteStand('couverture')).toBe('couverture');
    expect(readDensiteStand('confort')).toBe('noms');
    expect(readStandView('treemap')).toBe('treemap');
    expect(readStandView('frise')).toBe('grille');
  });

  it('lays one line per stand, alphabetically, the names in the cells', () => {
    const { lignes } = tableau();

    expect(lignes.map((ligne) => ligne.standNom)).toEqual(['Dixit', 'Tir']);
    const tir = lignes[1];
    expect(tir.emplacementNom).toBe('Halle');
    expect(tir.cases.map((cellule) => cellule.noms)).toEqual([['Alice M.'], ['Bruno P.']]);
  });

  it('tells closed from empty, and partial from full', () => {
    const [dixit, tir] = tableau().lignes;

    expect(dixit.cases.map((cellule) => cellule.statut)).toEqual(['ferme', 'vide']);
    expect(tir.cases.map((cellule) => cellule.statut)).toEqual(['partiel', 'pourvu']);
    expect(dixit.cases[0].active).toBe(false);
    expect(dixit.cases[0].libelle).toContain('fermé');
  });

  it('opens the Siège panel on the first empty seat of the day, else on its first seat', () => {
    const [dixit, tir] = tableau().lignes;

    expect(tir.cases[0].posteId).toBe('p2');
    expect(tir.cases[1].posteId).toBe('p3');
    expect(dixit.cases[1].posteId).toBe('p4');
  });

  it('sums the seats to fill, the filled ones, the share of hours held and the hours', () => {
    const tir = tableau().lignes[1];

    expect(tir.synthese['aPourvoir']?.texte).toBe('3');
    expect(tir.synthese['pourvus']?.texte).toBe('2');
    expect(tir.synthese['taux']?.texte).toBe('66 %');
    expect(tir.synthese['heures']?.texte).toBe('9');
  });

  it("totals each day over the lines on screen, the event's figures at the right", () => {
    const { pied } = tableau();
    expect(pied.cases).toEqual(['1/2', '1/2']);
    expect(pied.synthese['aPourvoir']).toBe('4');

    const filtre = tableau({ standsRetenus: new Set(['Tir']) });
    expect(filtre.pied.cases).toEqual(['1/2', '1/1']);
  });

  it('keeps the stands the filters name: a set of stands, a person, a text', () => {
    expect(tableau({ standsRetenus: new Set(['Dixit']) }).lignes.map((l) => l.standId)).toEqual([
      'Dixit',
    ]);
    expect(tableau({ animateur: 'b' }).lignes.map((l) => l.standId)).toEqual(['Tir']);
    expect(tableau({ recherche: 'bruno' }).lignes.map((l) => l.standId)).toEqual(['Tir']);
  });

  it('draws sixty-five stands over sixteen days without a hitch', () => {
    const jours: Creneau[] = Array.from({ length: 16 }, (_, index) => ({
      id: index + 1,
      jour: index + 1,
      date: `2026-09-${String(index + 1).padStart(2, '0')}`,
      heureDebut: '09:00',
      heureFin: '12:00',
    }));
    const postes: PosteAffectation[] = [];
    for (let s = 0; s < 65; s++) {
      const theStand = stand(`S${String(s).padStart(2, '0')}`);
      for (const creneau of jours) {
        postes.push(siege(`${s}-${creneau.id}-a`, theStand, creneau, ALICE));
        postes.push(siege(`${s}-${creneau.id}-b`, theStand, creneau, null));
      }
    }
    const debut = performance.now();
    const days = planningDays(postes);
    const grand = buildTableauStands(postes, days, joursGrille(days), {
      standsRetenus: null,
      animateur: '',
      recherche: '',
    });

    expect(grand.lignes).toHaveLength(65);
    expect(grand.lignes[0].cases).toHaveLength(16);
    // A budget, not a benchmark: the grid is rebuilt on every filter keystroke.
    expect(performance.now() - debut).toBeLessThan(500);
  });
});
