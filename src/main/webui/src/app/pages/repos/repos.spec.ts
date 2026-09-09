import { describe, expect, it } from 'vitest';
import { Animateur, ContrainteAdHoc, Creneau, PosteAffectation, Stand } from '../../core/models';
import { LigneRepos, buildTableauRepos, filtrerLignes, totauxParJour } from './repos';

function creneau(overrides: Partial<Creneau> & { id: number }): Creneau {
  return { jour: 1, date: '2026-08-01', heureDebut: '10:00', heureFin: '12:00', ...overrides };
}

function stand(id: string): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: [],
    effectifMin: 1,
    effectifMax: 1,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement: null,
    indisponibilites: [],
    ouvertures: [],
    horaires: []
  };
}

function animateur(id: string, overrides: Partial<Animateur> = {}): Animateur {
  return {
    id,
    prenom: id,
    nom: '',
    dateNaissance: '2000-01-01',
    manager: false,
    competences: {},
    souhaits: [],
    joursIndisponibles: [],
    ...overrides
  };
}

function poste(overrides: Partial<PosteAffectation> & { id: string }): PosteAffectation {
  return { stand: null, creneau: null, animateur: null, ...overrides };
}

function indisponibiliteForcee(animateurIds: string[], overrides: Partial<ContrainteAdHoc> = {}): ContrainteAdHoc {
  return {
    id: `c-${animateurIds.join('-')}`,
    type: 'INDISPONIBILITE_FORCEE',
    animateursConcernes: animateurIds.map((id) => ({ id })),
    creneau: null,
    stand: null,
    raison: 'test',
    ...overrides
  };
}

function ligne(lignes: LigneRepos[], nom: string): LigneRepos {
  const trouvee = lignes.find((candidate) => candidate.nom === nom);
  if (!trouvee) {
    throw new Error(`ligne introuvable : ${nom}`);
  }
  return trouvee;
}

/** Days 1 to 3 of the event, each with one seat, so a grid always has three columns. */
function troisJours(animateurs: (string | null)[]): PosteAffectation[] {
  return animateurs.map((id, index) =>
    poste({
      id: `p${index + 1}`,
      stand: stand('Tir'),
      creneau: creneau({ id: index + 1, jour: index + 1, date: `2026-08-0${index + 1}` }),
      animateur: id ? animateur(id) : null
    })
  );
}

describe('buildTableauRepos', () => {
  it('gives every animateur of the edition a line, assigned or not', () => {
    const tableau = buildTableauRepos(troisJours(['Ines', 'Ines', 'Ines']), [animateur('Ines'), animateur('Oscar')]);

    expect(tableau.lignes.map((each) => each.nom)).toEqual(['Ines', 'Oscar']);
    // Somebody the solver never used rests every day — that is the information.
    expect(ligne(tableau.lignes, 'Oscar').joursRepos).toBe(3);
    expect(ligne(tableau.lignes, 'Oscar').joursTravailles).toBe(0);
  });

  it('has one column per day the plan holds a créneau on', () => {
    const tableau = buildTableauRepos(troisJours(['Ines', null, 'Ines']), [animateur('Ines')]);

    expect(tableau.jours.map((each) => each.jour)).toEqual([1, 2, 3]);
    expect(tableau.jours.map((each) => each.date)).toEqual(['2026-08-01', '2026-08-02', '2026-08-03']);
  });

  it('counts an unassigned day as rest and a declared day as unavailable', () => {
    const tableau = buildTableauRepos(troisJours(['Ines', null, 'Ines']), [
      animateur('Ines', { joursIndisponibles: ['2026-08-02'] }),
      animateur('Oscar', { joursIndisponibles: ['2026-08-02'] })
    ]);

    const ines = ligne(tableau.lignes, 'Ines');
    expect(ines.cellules.map((each) => each.statut)).toEqual(['travaille', 'indisponible', 'travaille']);
    expect(ines.joursRepos).toBe(0);
    expect(ines.joursIndisponibles).toBe(1);
    // The two states are not merged: Oscar's day 2 is not a day off he was given.
    expect(ligne(tableau.lignes, 'Oscar').cellules.map((each) => each.statut)).toEqual([
      'repos',
      'indisponible',
      'repos'
    ]);
  });

  it('sums the hours worked each day, narrowed windows included', () => {
    const tableau = buildTableauRepos(
      [
        poste({ id: 'p1', stand: stand('Tir'), creneau: creneau({ id: 1 }), animateur: animateur('Ines') }),
        poste({
          id: 'p2',
          stand: stand('Dixit'),
          creneau: creneau({ id: 2, heureDebut: '14:00', heureFin: '18:00' }),
          // Partially closed stand (issue #60): the poste's own window wins.
          heureDebutEffective: '14:00',
          heureFinEffective: '16:30',
          animateur: animateur('Ines')
        })
      ],
      [animateur('Ines')]
    );

    const cellule = ligne(tableau.lignes, 'Ines').cellules[0];
    expect(cellule.postes).toBe(2);
    expect(cellule.minutes).toBe(120 + 150);
    expect(cellule.label).toBe('4 h 30');
  });

  it('measures the longest run of consecutive worked days', () => {
    const cinqJours = [true, true, false, true, true].map((travaille, index) =>
      poste({
        id: `p${index}`,
        stand: stand('Tir'),
        creneau: creneau({ id: index, jour: index + 1, date: `2026-08-0${index + 1}` }),
        animateur: travaille ? animateur('Ines') : null
      })
    );

    expect(ligne(buildTableauRepos(cinqJours, [animateur('Ines')]).lignes, 'Ines').serieMax).toBe(2);
  });

  it('flags only the animateurs working every single day', () => {
    const tableau = buildTableauRepos(
      [
        ...troisJours(['Ines', 'Ines', 'Ines']),
        poste({ id: 'q2', stand: stand('Dixit'), creneau: creneau({ id: 12, jour: 2, date: '2026-08-02' }), animateur: animateur('Zoe') }),
        poste({ id: 'q3', stand: stand('Dixit'), creneau: creneau({ id: 13, jour: 3, date: '2026-08-03' }), animateur: animateur('Zoe') })
      ],
      // Zoé works days 2 and 3 and was unavailable on day 1: she did not work
      // every day, so she is not the one to look at, however busy she is.
      [animateur('Ines'), animateur('Zoe', { joursIndisponibles: ['2026-08-01'] })]
    );

    expect(ligne(tableau.lignes, 'Ines').sansRepos).toBe(true);
    expect(ligne(tableau.lignes, 'Zoe').sansRepos).toBe(false);
    expect(ligne(tableau.lignes, 'Ines').resume).toContain('aucun jour de repos');
  });

  it('names an assignment landing on a day declared unavailable', () => {
    const tableau = buildTableauRepos(troisJours(['Ines', 'Ines', 'Ines']), [
      animateur('Ines', { joursIndisponibles: ['2026-08-02'] })
    ]);

    const cellules = ligne(tableau.lignes, 'Ines').cellules;
    // The person is on the plan, so the day stays "worked" — the anomaly is
    // named on top of it rather than hiding the seat.
    expect(cellules[1].statut).toBe('travaille');
    expect(cellules[1].conflit).toBe(true);
    expect(cellules[1].tooltip).toContain('déclarée indisponible');
    expect(cellules[0].conflit).toBe(false);
  });

  it('reads a whole-event forced unavailability, and only that one', () => {
    const postes = troisJours([null, null, null]);

    const ecarte = buildTableauRepos(postes, [animateur('Zoe')], [indisponibiliteForcee(['Zoe'])]);
    expect(ligne(ecarte.lignes, 'Zoe').joursIndisponibles).toBe(3);

    // Scoped to one créneau or one stand, the exception still leaves the day
    // workable: counting it as a day off would invent rest nobody granted.
    const target = buildTableauRepos(postes, [animateur('Zoe')], [
      indisponibiliteForcee(['Zoe'], { creneau: { id: 1 } }),
      indisponibiliteForcee(['Zoe'], { stand: { id: 'Tir' } })
    ]);
    expect(ligne(target.lignes, 'Zoe').joursIndisponibles).toBe(0);
    expect(ligne(target.lignes, 'Zoe').joursRepos).toBe(3);
  });

  it('puts the longest runs first, so the top of the grid is the list to act on', () => {
    const postes = [
      ...troisJours(['Ines', 'Ines', 'Ines']),
      poste({ id: 'q1', stand: stand('Dixit'), creneau: creneau({ id: 21, jour: 1, date: '2026-08-01' }), animateur: animateur('Alice') })
    ];

    const tableau = buildTableauRepos(postes, [animateur('Alice'), animateur('Ines'), animateur('Zoe')]);

    expect(tableau.lignes.map((each) => each.nom)).toEqual(['Ines', 'Alice', 'Zoe']);
  });

  it('keeps a line for an animateur missing from the referential but holding a seat', () => {
    const tableau = buildTableauRepos(troisJours(['Ines', 'Ines', 'Ines']), []);

    expect(tableau.lignes.map((each) => each.nom)).toEqual(['Ines']);
  });

  it('tells apart two animateurs sharing a name', () => {
    const tableau = buildTableauRepos(troisJours([null, null, null]), [
      animateur('a1', { prenom: 'Ines', nom: 'Dupont' }),
      animateur('a2', { prenom: 'Ines', nom: 'Dupont' })
    ]);

    expect(tableau.lignes.map((each) => each.nom)).toEqual(['Ines Dupont (a1)', 'Ines Dupont (a2)']);
  });
});

describe('totauxParJour', () => {
  it('counts, day by day, who works, who rests and who was unavailable', () => {
    const tableau = buildTableauRepos(troisJours(['Ines', null, 'Ines']), [
      animateur('Ines'),
      animateur('Oscar', { joursIndisponibles: ['2026-08-02'] })
    ]);

    expect(totauxParJour(tableau.jours, tableau.lignes)).toEqual([
      { jour: 1, travaillent: 1, repos: 1, indisponibles: 0 },
      { jour: 2, travaillent: 0, repos: 1, indisponibles: 1 },
      { jour: 3, travaillent: 1, repos: 1, indisponibles: 0 }
    ]);
  });

  it('counts the rows it is handed, so a filtered grid keeps an honest footer', () => {
    const tableau = buildTableauRepos(troisJours(['Ines', null, 'Ines']), [animateur('Ines'), animateur('Oscar')]);

    const totaux = totauxParJour(tableau.jours, filtrerLignes(tableau.lignes, 'Oscar', false));

    expect(totaux.map((each) => each.repos)).toEqual([1, 1, 1]);
  });
});

describe('filtrerLignes', () => {
  const lignes = buildTableauRepos(troisJours(['Ines', 'Ines', 'Ines']), [
    animateur('a1', { prenom: 'Inès', nom: 'Dupont' }),
    animateur('Ines'),
    animateur('a2', { prenom: 'Oscar', nom: 'Martin' })
  ]).lignes;

  it('matches the name whatever its accents and its case', () => {
    expect(filtrerLignes(lignes, 'ines dup', false).map((each) => each.nom)).toEqual(['Inès Dupont']);
  });

  it('keeps everything on an empty search', () => {
    expect(filtrerLignes(lignes, '   ', false)).toHaveLength(3);
  });

  it('narrows to the animateurs without a single rest day', () => {
    expect(filtrerLignes(lignes, '', true).map((each) => each.nom)).toEqual(['Ines']);
  });
});
