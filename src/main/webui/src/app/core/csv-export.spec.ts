import { describe, expect, it } from 'vitest';
import { csvFileName, toCsv } from './csv-export';

describe('csv-export', () => {
  it('writes the displayed rows with a byte order mark, semicolons and quoted fields', () => {
    const csv = toCsv(
      [
        { nom: 'Loup; Garou', typologies: ['Ambiance', 'Expert'], effectif: 2 },
        { nom: 'Dixit "le"', typologies: [], effectif: null },
      ],
      [
        { title: 'Nom', value: (row) => row.nom },
        { title: 'Typologies', value: (row) => row.typologies },
        { title: 'Effectif', value: (row) => row.effectif },
      ],
    );
    expect(csv).toBe(
      '﻿Nom;Typologies;Effectif\r\n"Loup; Garou";Ambiance|Expert;2\r\n"Dixit ""le""";;\r\n',
    );
  });

  it('names the file after the list and the day', () => {
    expect(csvFileName('stands', new Date(2026, 8, 6))).toBe('stands-2026-09-06.csv');
  });
});
