import { describe, expect, it } from 'vitest';
import { CIBLES_EXPORT_CSV } from './export-csv-targets';

describe('CIBLES_EXPORT_CSV', () => {
  // Only the animateurs' file is read back by the application: the others are
  // for a spreadsheet, and the YAML file is the way back in (ADR 0134).
  it('offers the six referentials of the archive, in the order data is entered', () => {
    expect([...CIBLES_EXPORT_CSV]).toEqual([
      'TYPOLOGIES',
      'EMPLACEMENTS',
      'STANDS',
      'CRENEAUX',
      'JOURNEES_TYPES',
      'ANIMATEURS',
    ]);
  });
});
