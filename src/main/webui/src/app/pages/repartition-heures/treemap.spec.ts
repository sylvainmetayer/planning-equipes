import { describe, expect, it } from 'vitest';
import { Animateur, Creneau, Emplacement, PosteAffectation, Stand } from '../../core/models';
import {
  GROUP_HEADER,
  HoursInput,
  HoursNode,
  NO_EMPLACEMENT,
  PlacedRect,
  Rect,
  aggregateHours,
  coverageLevel,
  findPath,
  knownStands,
  layoutTiles,
  mondayOf,
  periodChoices,
  seatMinutes,
  squarify,
  tableRows,
  withOthers,
} from './treemap';

const PLACE: Emplacement = { id: 'PLACE', nom: 'Place', latitude: null, longitude: null };
const HALLE: Emplacement = { id: 'HALLE', nom: 'Halle', latitude: null, longitude: null };

function stand(id: string, emplacement: Emplacement | null, typologies: string[] = []): Stand {
  return {
    id,
    nom: id,
    typologiesProposees: typologies,
    effectifMin: 1,
    effectifMax: 3,
    reserveMajeurs: false,
    premium: false,
    niveauEffort: 'NORMAL',
    emplacement,
    indisponibilites: [],
    ouvertures: [],
    horaires: [],
  };
}

function creneau(id: number, date: string, heureDebut: string, heureFin: string): Creneau {
  return { id, jour: id, date, heureDebut, heureFin };
}

const SOMEBODY: Animateur = {
  id: 'A1',
  prenom: 'X',
  nom: 'Y',
  dateNaissance: '1990-01-01',
  manager: false,
  competences: {},
  souhaits: [],
  joursIndisponibles: [],
};

let counter = 0;
function seat(
  of: Stand,
  slot: Creneau,
  filled: boolean,
  overrides: Partial<PosteAffectation> = {},
): PosteAffectation {
  counter += 1;
  return {
    id: `P${counter}`,
    stand: of,
    creneau: slot,
    animateur: filled ? SOMEBODY : null,
    ...overrides,
  };
}

const LABELS = {
  root: 'Édition',
  noEmplacement: 'Sans emplacement',
  noTypologie: 'Sans typologie',
};

function input(overrides: Partial<HoursInput>): HoursInput {
  return {
    postes: [],
    stands: [],
    grouping: 'stand',
    period: { kind: 'all' },
    emplacementId: null,
    typologieLabels: new Map([
      ['AMB', 'Ambiance'],
      ['STRAT', 'Stratégie'],
    ]),
    labels: LABELS,
    ...overrides,
  };
}

const MONDAY = creneau(1, '2026-07-06', '10:00', '12:00'); // a Monday, 2 h
const WEDNESDAY = creneau(2, '2026-07-08', '14:00', '18:00'); // 4 h
const NEXT_MONDAY = creneau(3, '2026-07-13', '10:00', '11:00'); // 1 h

function area(rect: Rect): number {
  return rect.width * rect.height;
}

function overlap(a: Rect, b: Rect): number {
  const width = Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x);
  const height = Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y);
  return width > 0 && height > 0 ? width * height : 0;
}

function aspect(rect: Rect): number {
  return Math.max(rect.width / rect.height, rect.height / rect.width);
}

function expectTiling(placed: PlacedRect[], bounds: Rect): void {
  const total = placed.reduce((sum, rect) => sum + area(rect), 0);
  expect(total).toBeCloseTo(area(bounds), 6);
  for (let i = 0; i < placed.length; i++) {
    const rect = placed[i];
    expect(rect.x).toBeGreaterThanOrEqual(bounds.x - 1e-9);
    expect(rect.y).toBeGreaterThanOrEqual(bounds.y - 1e-9);
    expect(rect.x + rect.width).toBeLessThanOrEqual(bounds.x + bounds.width + 1e-9);
    expect(rect.y + rect.height).toBeLessThanOrEqual(bounds.y + bounds.height + 1e-9);
    for (let j = i + 1; j < placed.length; j++) {
      expect(overlap(rect, placed[j])).toBeLessThan(1e-6);
    }
  }
}

describe('seatMinutes', () => {
  it('counts the effective window when a partial closure narrowed it', () => {
    const narrowed = seat(stand('S', null), WEDNESDAY, true, { heureDebutEffective: '16:00' });
    expect(seatMinutes(narrowed)).toBe(120);
    expect(seatMinutes(seat(stand('S', null), WEDNESDAY, true))).toBe(240);
  });

  it('reads a midnight end as the end of the day', () => {
    expect(
      seatMinutes(seat(stand('S', null), creneau(9, '2026-07-06', '22:00', '00:00'), true)),
    ).toBe(120);
  });
});

describe('periods', () => {
  it('files a date under the Monday of its week', () => {
    expect(mondayOf('2026-07-06')).toBe('2026-07-06');
    expect(mondayOf('2026-07-12')).toBe('2026-07-06');
    expect(mondayOf('2026-07-13')).toBe('2026-07-13');
  });

  it('lists the days carrying a seat and their weeks', () => {
    const s = stand('S', null);
    const choices = periodChoices([
      seat(s, NEXT_MONDAY, true),
      seat(s, WEDNESDAY, true),
      seat(s, MONDAY, false),
    ]);
    expect(choices.days).toEqual(['2026-07-06', '2026-07-08', '2026-07-13']);
    expect(choices.weeks).toEqual(['2026-07-06', '2026-07-13']);
  });
});

describe('coverageLevel', () => {
  it('reads its own thresholds: under 80 % critical, then partial, then full', () => {
    expect(coverageLevel(0, 0)).toBe('none');
    expect(coverageLevel(79, 100)).toBe('critical');
    expect(coverageLevel(80, 100)).toBe('partial');
    expect(coverageLevel(99, 100)).toBe('partial');
    expect(coverageLevel(100, 100)).toBe('full');
  });
});

describe('aggregateHours', () => {
  const tir = stand('Tir', PLACE, ['AMB']);
  const dixit = stand('Dixit', PLACE, ['STRAT', 'AMB']);
  const buvette = stand('Buvette', HALLE, ['AMB']);
  const errant = stand('Errant', null, []);
  const postes = [
    seat(tir, MONDAY, true),
    seat(tir, MONDAY, false),
    seat(dixit, WEDNESDAY, true),
    seat(buvette, NEXT_MONDAY, true),
    seat(errant, MONDAY, true),
  ];
  const stands = [tir, dixit, buvette, errant];

  it('nests stands in their emplacement, the stands tied to none in « Sans emplacement »', () => {
    const tree = aggregateHours(input({ postes, stands }));
    expect(tree.requiredMinutes).toBe(120 * 2 + 240 + 60 + 120);
    expect(tree.filledMinutes).toBe(120 + 240 + 60 + 120);
    expect(tree.children.map((group) => group.label)).toEqual([
      'Place',
      'Sans emplacement',
      'Halle',
    ]);
    expect(tree.children[0].children.map((leaf) => leaf.label)).toEqual(['Dixit', 'Tir']);
    expect(tree.children[1]).toMatchObject({ id: 'e:', standCount: 1 });
  });

  it('files a multi-typologie stand under its combination, so the surfaces add up to the total', () => {
    const tree = aggregateHours(input({ postes, stands, grouping: 'typologie' }));
    const labels = tree.children.map((group) => group.label);
    expect(labels).toContain('Ambiance + Stratégie');
    expect(labels).toContain('Sans typologie');
    const combination = tree.children.find((group) => group.label === 'Ambiance + Stratégie')!;
    expect(combination.children.map((leaf) => leaf.standId)).toEqual(['Dixit']);
    const sum = tree.children.reduce((total, group) => total + group.requiredMinutes, 0);
    expect(sum).toBe(tree.requiredMinutes);
    expect(tree.requiredMinutes).toBe(aggregateHours(input({ postes, stands })).requiredMinutes);
  });

  it('counts a period only, and keeps a stand with no seat in it at 0 h', () => {
    const tree = aggregateHours(
      input({ postes, stands, period: { kind: 'week', monday: '2026-07-13' } }),
    );
    expect(tree.requiredMinutes).toBe(60);
    const place = tree.children.find((group) => group.id === 'e:PLACE')!;
    expect(place.children.map((leaf) => leaf.requiredMinutes)).toEqual([0, 0]);

    const day = aggregateHours(
      input({ postes, stands, period: { kind: 'day', date: '2026-07-08' } }),
    );
    expect(day.requiredMinutes).toBe(240);
  });

  it('keeps a stand met on a seat but missing from the referential', () => {
    const tree = aggregateHours(input({ postes, stands: [] }));
    expect(tree.standCount).toBe(4);
  });

  it('filters by emplacement, « aucun » meaning the stands tied to none', () => {
    const halle = aggregateHours(input({ postes, stands, emplacementId: 'HALLE' }));
    expect(tree(halle)).toEqual(['Buvette']);
    const none = aggregateHours(input({ postes, stands, emplacementId: NO_EMPLACEMENT }));
    expect(tree(none)).toEqual(['Errant']);
  });

  function tree(root: HoursNode): (string | null)[] {
    return root.children.flatMap((group) => group.children.map((leaf) => leaf.standId));
  }
});

describe('withOthers', () => {
  function leaf(id: string, minutes: number): HoursNode {
    return {
      id: `s:${id}`,
      label: id,
      kind: 'stand',
      standId: id,
      requiredMinutes: minutes,
      filledMinutes: minutes,
      standCount: 1,
      children: [],
    };
  }
  function root(children: HoursNode[]): HoursNode {
    return {
      id: 'root',
      label: 'root',
      kind: 'root',
      standId: null,
      requiredMinutes: children.reduce((sum, child) => sum + child.requiredMinutes, 0),
      filledMinutes: 0,
      standCount: children.length,
      children,
    };
  }
  const label = (count: number) => `Autres (${count} stands)`;

  it('gathers the slivers under « Autres » when one stand weighs more than half', () => {
    const shown = withOthers(
      root([leaf('Big', 900), leaf('Mid', 60), leaf('A', 20), leaf('B', 15), leaf('C', 5)]),
      label,
    );
    expect(shown.children.map((child) => child.label)).toEqual(['Big', 'Mid', 'Autres (3 stands)']);
    const others = shown.children[2];
    expect(others).toMatchObject({
      kind: 'others',
      id: 'o:root',
      requiredMinutes: 40,
      standCount: 3,
    });
    expect(findPath(shown, 's:B')!.map((node) => node.id)).toEqual(['root', 'o:root', 's:B']);
  });

  it('leaves a balanced level alone, and drops what weighs nothing', () => {
    const shown = withOthers(root([leaf('A', 50), leaf('B', 50), leaf('Z', 0)]), label);
    expect(shown.children.map((child) => child.label)).toEqual(['A', 'B']);
  });
});

describe('squarify', () => {
  const bounds: Rect = { x: 0, y: 0, width: 1000, height: 600 };

  it('tiles the bounds exactly, in proportion, with no overlap', () => {
    const values = [6, 6, 4, 3, 2, 2, 1];
    const placed = squarify(
      values.map((value, index) => ({ id: String(index), value })),
      bounds,
    );
    expect(placed).toHaveLength(values.length);
    expectTiling(placed, bounds);
    const total = values.reduce((sum, value) => sum + value, 0);
    for (const rect of placed) {
      expect(area(rect) / area(bounds)).toBeCloseTo(values[Number(rect.id)] / total, 6);
    }
  });

  it('keeps the aspect ratio of every tile bounded', () => {
    const equal = squarify(
      Array.from({ length: 12 }, (_, index) => ({ id: String(index), value: 1 })),
      bounds,
    );
    expect(Math.max(...equal.map(aspect))).toBeLessThan(2.5);
    const skewed = squarify(
      [500, 250, 120, 60, 30, 20, 10, 8, 5, 3].map((value, index) => ({
        id: String(index),
        value,
      })),
      bounds,
    );
    expectTiling(skewed, bounds);
    expect(Math.max(...skewed.map(aspect))).toBeLessThan(4);
  });

  it('leaves out what weighs nothing, and answers nothing for an empty set', () => {
    expect(squarify([{ id: 'a', value: 0 }], bounds)).toEqual([]);
    expect(squarify([], bounds)).toEqual([]);
    expect(
      squarify(
        [
          { id: 'a', value: 3 },
          { id: 'b', value: 0 },
        ],
        bounds,
      ).map((r) => r.id),
    ).toEqual(['a']);
  });
});

describe('layoutTiles', () => {
  it('draws the grandchildren inside their group, below its title', () => {
    const tir = stand('Tir', PLACE);
    const dixit = stand('Dixit', PLACE);
    const buvette = stand('Buvette', HALLE);
    const tree = aggregateHours(
      input({
        postes: [
          seat(tir, MONDAY, true),
          seat(dixit, WEDNESDAY, false),
          seat(buvette, MONDAY, true),
        ],
        stands: [tir, dixit, buvette],
      }),
    );
    const bounds: Rect = { x: 0, y: 0, width: 1000, height: 600 };
    const tiles = layoutTiles(tree, bounds);
    const groups = tiles.filter((tile) => tile.depth === 1);
    expectTiling(
      groups.map((tile) => ({
        id: tile.node.id,
        x: tile.x,
        y: tile.y,
        width: tile.width,
        height: tile.height,
      })),
      bounds,
    );
    for (const tile of tiles.filter((candidate) => candidate.depth === 2)) {
      const parent = groups.find((group) => group.node.children.includes(tile.node))!;
      expect(tile.y).toBeGreaterThanOrEqual(parent.y + GROUP_HEADER);
      expect(tile.x + tile.width).toBeLessThanOrEqual(parent.x + parent.width + 1e-9);
      expect(tile.y + tile.height).toBeLessThanOrEqual(parent.y + parent.height + 1e-9);
    }
  });

  it("keeps a group's title clear at phone width, whatever room the title asks", () => {
    const tir = stand('Tir', PLACE);
    const dixit = stand('Dixit', PLACE);
    const tree = aggregateHours(
      input({
        postes: [seat(tir, MONDAY, true), seat(dixit, WEDNESDAY, false)],
        stands: [tir, dixit],
      }),
    );
    // A 320 px wide card, and a title drawn at a 20 px root font.
    const bounds: Rect = { x: 0, y: 0, width: 320, height: 192 };
    const header = 50;
    const tiles = layoutTiles(tree, bounds, header);
    const group = tiles.find((tile) => tile.depth === 1)!;
    const inner = tiles.filter((tile) => tile.depth === 2);
    expect(inner).toHaveLength(2);
    for (const tile of inner) {
      expect(tile.y).toBeGreaterThanOrEqual(group.y + header);
    }
    const covered = inner.reduce((sum, tile) => sum + tile.width * tile.height, 0);
    expect(covered).toBeCloseTo((320 - 6) * (192 - header - 3), 6);
  });

  it('draws no grandchild in a group too short for its own title', () => {
    const tir = stand('Tir', PLACE);
    const tree = aggregateHours(input({ postes: [seat(tir, MONDAY, true)], stands: [tir] }));
    const tiles = layoutTiles(tree, { x: 0, y: 0, width: 320, height: 30 }, GROUP_HEADER);
    expect(tiles.map((tile) => tile.depth)).toEqual([1]);
  });
});

describe('knownStands', () => {
  it('lists the referential first, then the stands only a seat knows, each once', () => {
    const tir = stand('Tir', PLACE);
    const ghost = stand('Ghost', null);
    expect(
      knownStands(
        [tir],
        [seat(tir, MONDAY, true), seat(ghost, MONDAY, false), seat(ghost, WEDNESDAY, true)],
      ).map((known) => known.id),
    ).toEqual(['Tir', 'Ghost']);
  });
});

describe('tableRows', () => {
  it('lists every group then its stands, 0 h stands included', () => {
    const tir = stand('Tir', PLACE);
    const idle = stand('Idle', PLACE);
    const tree = aggregateHours(input({ postes: [seat(tir, MONDAY, true)], stands: [tir, idle] }));
    expect(tableRows(tree).map((row) => [row.node.label, row.depth])).toEqual([
      ['Place', 0],
      ['Tir', 1],
      ['Idle', 1],
    ]);
  });
});
