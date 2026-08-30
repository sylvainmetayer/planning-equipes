import { describe, expect, it } from 'vitest';
import { readPanelCollapsed, writePanelCollapsed } from './panel-collapse';

/** A storage that is there and works, like a normal browser's. */
function storage(initial: Record<string, string> = {}): Pick<Storage, 'getItem' | 'setItem'> {
  const entries = new Map(Object.entries(initial));
  return {
    getItem: (key) => entries.get(key) ?? null,
    setItem: (key, value) => void entries.set(key, value)
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
    }
  };
}

const CLE = 'planning-equipes.solver.scoreCurveCollapsed';

describe('panel-collapse', () => {
  it('déplie par défaut : rien n’a jamais été écrit', () => {
    expect(readPanelCollapsed(storage(), CLE)).toBe(false);
  });

  it('relit ce qui a été écrit', () => {
    const memoire = storage();
    writePanelCollapsed(memoire, CLE, true);

    expect(readPanelCollapsed(memoire, CLE)).toBe(true);
  });

  it('rouvre le panneau quand on le déplie', () => {
    const memoire = storage({ [CLE]: 'true' });
    writePanelCollapsed(memoire, CLE, false);

    expect(readPanelCollapsed(memoire, CLE)).toBe(false);
  });

  it('garde chaque panneau indépendant de ses voisins', () => {
    const memoire = storage();
    writePanelCollapsed(memoire, CLE, true);

    expect(readPanelCollapsed(memoire, 'planning-equipes.autre.panneau')).toBe(false);
  });

  it('déplie plutôt que de croire une valeur écrite par une version antérieure', () => {
    // Masquer un panneau que l'utilisateur attend lui coûte la fonctionnalité ;
    // en afficher un qu'il avait rangé ne lui coûte que de la place.
    expect(readPanelCollapsed(storage({ [CLE]: 'oui' }), CLE)).toBe(false);
  });

  it('survit à une absence de stockage, et à un stockage qui lève', () => {
    expect(readPanelCollapsed(null, CLE)).toBe(false);
    expect(readPanelCollapsed(storageQuiRefuse(), CLE)).toBe(false);
    // Ni l'un ni l'autre ne doit faire tomber l'écran au moment du clic.
    expect(() => writePanelCollapsed(null, CLE, true)).not.toThrow();
    expect(() => writePanelCollapsed(storageQuiRefuse(), CLE, true)).not.toThrow();
  });
});
