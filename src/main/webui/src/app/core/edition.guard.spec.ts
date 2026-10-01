import { describe, expect, it } from 'vitest';
import { firstEditionChoice } from './edition.guard';
import { Edition } from './models';

function edition(id: string, active = false): Edition {
  return { id, nom: id, active, creeLe: null };
}

describe('firstEditionChoice', () => {
  it('lands a browser that never chose on the active edition', () => {
    expect(firstEditionChoice([edition('E1'), edition('E2', true), edition('E3')])?.id).toBe('E2');
  });

  it('lands it on the most recent edition between two events', () => {
    expect(firstEditionChoice([edition('E1'), edition('E2')])?.id).toBe('E2');
  });

  it('has nothing to choose from an empty list', () => {
    expect(firstEditionChoice([])).toBeNull();
  });
});
