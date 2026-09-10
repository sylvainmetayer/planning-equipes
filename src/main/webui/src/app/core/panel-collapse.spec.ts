import { describe, expect, it } from 'vitest';
import { readPanelCollapsed, writePanelCollapsed } from './panel-collapse';

/** A storage that is there and works, like a normal browser's. */
function storage(initial: Record<string, string> = {}): Pick<Storage, 'getItem' | 'setItem'> {
  const entries = new Map(Object.entries(initial));
  return {
    getItem: (key) => entries.get(key) ?? null,
    setItem: (key, value) => void entries.set(key, value),
  };
}

/** A hardened browser: touching storage *throws* rather than returning null. */
function storageQuiRefuse(): Pick<Storage, 'getItem' | 'setItem'> {
  return {
    getItem: () => {
      throw new Error('storage refusée');
    },
    setItem: () => {
      throw new Error('storage refusée');
    },
  };
}

const KEY = 'planning-equipes.solver.scoreCurveCollapsed';

describe('panel-collapse', () => {
  it('déplie par défaut : rien n’a jamais été écrit', () => {
    expect(readPanelCollapsed(storage(), KEY)).toBe(false);
  });

  it('relit ce qui a été écrit', () => {
    const memoire = storage();
    writePanelCollapsed(memoire, KEY, true);

    expect(readPanelCollapsed(memoire, KEY)).toBe(true);
  });

  it('rouvre le panneau quand on le déplie', () => {
    const memoire = storage({ [KEY]: 'true' });
    writePanelCollapsed(memoire, KEY, false);

    expect(readPanelCollapsed(memoire, KEY)).toBe(false);
  });

  it('garde chaque panneau indépendant de ses voisins', () => {
    const memoire = storage();
    writePanelCollapsed(memoire, KEY, true);

    expect(readPanelCollapsed(memoire, 'planning-equipes.autre.panneau')).toBe(false);
  });

  it('déplie plutôt que de croire une valeur écrite par une version antérieure', () => {
    // Masquer un panneau que l'utilisateur attend lui coûte la fonctionnalité ;
    // en afficher un qu'il avait rangé ne lui coûte que de la place.
    expect(readPanelCollapsed(storage({ [KEY]: 'oui' }), KEY)).toBe(false);
  });

  it('survit à une absence de stockage, et à un stockage qui lève', () => {
    expect(readPanelCollapsed(null, KEY)).toBe(false);
    expect(readPanelCollapsed(storageQuiRefuse(), KEY)).toBe(false);
    // Ni l'un ni l'autre ne doit faire tomber l'écran au moment du clic.
    expect(() => writePanelCollapsed(null, KEY, true)).not.toThrow();
    expect(() => writePanelCollapsed(storageQuiRefuse(), KEY, true)).not.toThrow();
  });
});
