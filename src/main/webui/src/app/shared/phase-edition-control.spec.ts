// The phase control: the four phases with the current one marked, the gestures
// of a writer, and nothing to click for a lecteur.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionStore } from '../core/edition.store';
import { Edition } from '../core/models';
import { SessionStore } from '../core/session.store';
import { seedStore } from '../core/testing/seed-store';
import { PhaseEditionActions } from './phase-edition-actions';
import { PhaseEditionControl } from './phase-edition-control';

function edition(partiel: Partial<Edition>): Edition {
  return { id: 'E1', nom: 'Année 2026', active: true, creeLe: null, ...partiel };
}

describe('PhaseEditionControl', () => {
  let fixture: ComponentFixture<PhaseEditionControl>;
  let store: EditionStore;
  const actions = {
    busy: () => false,
    changePhase: vi.fn(),
    unblockStructure: vi.fn(),
    refreezeStructure: vi.fn(),
  };
  const session = { lectureSeule: vi.fn(() => false) };

  beforeEach(() => {
    actions.changePhase.mockReset();
    actions.unblockStructure.mockReset();
    actions.refreezeStructure.mockReset();
    session.lectureSeule.mockReturnValue(false);
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: PhaseEditionActions, useValue: actions },
        { provide: SessionStore, useValue: session },
      ],
    });
    store = TestBed.inject(EditionStore);
    fixture = TestBed.createComponent(PhaseEditionControl);
  });

  const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const buttons = (): string[] =>
    Array.from(root().querySelectorAll('button')).map((b) => b.textContent!.trim());

  async function show(courant: Edition): Promise<void> {
    seedStore(store, 'courant', courant);
    await fixture.whenStable();
  }

  it('marks the current phase and offers the three others', async () => {
    await show(edition({ phase: 'CALCUL', phaseDepuis: '2026-07-01T08:00:00Z' }));

    expect(root().querySelector('li[aria-current="step"]')!.textContent).toContain('Calcul');
    expect(buttons()).toEqual(['Passer en Préparation', 'Passer en Publiée', 'Passer en Terminée']);
    expect(root().textContent).toContain('figés');
  });

  it('asks the action to move to the phase clicked', async () => {
    await show(edition({ phase: 'PREPARATION' }));

    root().querySelector<HTMLButtonElement>('li[data-phase="PUBLIEE"] button')!.click();

    expect(actions.changePhase).toHaveBeenCalledWith('PUBLIEE');
  });

  it('offers to unblock the structure in Publiée, and to freeze it again once unblocked', async () => {
    await show(edition({ phase: 'PUBLIEE', structureDebloquee: false }));
    expect(buttons()).toContain('Débloquer la structure');
    expect(buttons()).not.toContain('Refiger');
    root()
      .querySelectorAll<HTMLButtonElement>('button')
      .forEach((b) => b.textContent!.includes('Débloquer') && b.click());
    expect(actions.unblockStructure).toHaveBeenCalledOnce();

    await show(edition({ phase: 'PUBLIEE', structureDebloquee: true }));
    expect(root().querySelector('[role="status"]')!.textContent).toContain('Structure débloquée');
    expect(buttons()).toContain('Refiger');
    expect(buttons()).not.toContain('Débloquer la structure');
  });

  it('offers no unblocking outside Publiée', async () => {
    await show(edition({ phase: 'CALCUL' }));
    expect(buttons().join('|')).not.toContain('Débloquer');
  });

  it('shows the phase to a lecteur and gives no button', async () => {
    session.lectureSeule.mockReturnValue(true);
    await show(edition({ phase: 'PUBLIEE', structureDebloquee: true }));

    expect(root().querySelector('li[aria-current="step"]')!.textContent).toContain('Publiée');
    expect(root().querySelectorAll('button')).toHaveLength(0);
  });
});
