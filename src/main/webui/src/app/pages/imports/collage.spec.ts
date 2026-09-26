import { describe, expect, it } from 'vitest';
import { parseTsv, pasteToCsv } from './collage';

describe('pasteToCsv', () => {
  it('turns the tabs of a spreadsheet paste into quoted cells the server cannot misread', () => {
    expect(pasteToCsv('code\tnom\nA\tStand A\n')).toBe('"code";"nom"\n"A";"Stand A"\n');
  });

  /** A cell full of commas must not make the server take the comma for the separator. */
  it('keeps the commas of a cell inside its quotes', () => {
    const csv = pasteToCsv('nom\tvacations\nSemaine\t09:00-12:00, 12:00-13:00 R, 14:00-20:00');

    expect(csv).toBe('"nom";"vacations"\n"Semaine";"09:00-12:00, 12:00-13:00 R, 14:00-20:00"\n');
  });

  it('doubles the quotes a cell holds', () => {
    expect(pasteToCsv('libelle\tcode\nLe "grand" jeu\tG')).toBe(
      '"libelle";"code"\n"Le ""grand"" jeu";"G"\n',
    );
  });

  it('drops the empty lines a spreadsheet adds after its last row, and its carriage returns', () => {
    expect(pasteToCsv('a\tb\r\n1\t2\r\n\r\n')).toBe('"a";"b"\n"1";"2"\n');
  });

  it('leaves a text without any tab as it is: that is CSV already', () => {
    expect(pasteToCsv('code;nom\nA;Stand A\n')).toBe('code;nom\nA;Stand A\n');
  });

  /** Excel and LibreOffice quote a cell holding a line break: it stays one cell of one row. */
  it('keeps a quoted cell with a line break in one cell', () => {
    const csv = pasteToCsv('nom\tdescription\nCirque\t"Deux lignes\nde texte"\nMagie\tcourt\n');

    expect(csv).toBe('"nom";"description"\n"Cirque";"Deux lignes\nde texte"\n"Magie";"court"\n');
  });

  it('reads the doubled quotes of a quoted cell as the quotes it holds', () => {
    expect(pasteToCsv('libelle\tcode\n"Le ""grand"" jeu"\tG\n')).toBe(
      '"libelle";"code"\n"Le ""grand"" jeu";"G"\n',
    );
  });
});

describe('parseTsv', () => {
  it('splits rows on line breaks and cells on tabs, empty cells included', () => {
    expect(parseTsv('a\t\tc\nd\te\tf')).toEqual([
      ['a', '', 'c'],
      ['d', 'e', 'f'],
    ]);
  });

  it('reads a quoted cell up to its closing quote, tab and line break included', () => {
    expect(parseTsv('"x\ty"\t"1\n2"')).toEqual([['x\ty', '1\n2']]);
  });

  /** A cell that only looks quoted is taken as typed, rather than losing its quotes. */
  it('reads a cell whose quote does not close before a separator as it was typed', () => {
    expect(parseTsv('"grand" jeu\tG\n"ouvert\tH')).toEqual([
      ['"grand" jeu', 'G'],
      ['"ouvert', 'H'],
    ]);
  });
});
