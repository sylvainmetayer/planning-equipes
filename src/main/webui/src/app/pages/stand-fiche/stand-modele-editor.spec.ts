// The model editor of the fiche stand: it writes the stand through the CRUD
// service, with the rules typed and nothing else changed.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { Stand } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SessionStore } from '../../core/session.store';
import { fakeOf, provideFake } from '../../core/testing/fake';
import { StandModeleEditor } from './stand-modele-editor';

const STAND: Stand = {
  id: 'S1',
  nom: 'Stand 1',
  typologiesProposees: ['T1'],
  effectifMin: 2,
  effectifMax: 3,
  reserveMajeurs: false,
  premium: false,
  niveauEffort: 'NORMAL',
  emplacement: null,
  modifieLe: '2026-07-01T10:00:00Z',
  plages: [],
  indisponibilites: [],
  ouvertures: [],
  horaires: [],
};

describe('StandModeleEditor', () => {
  let fixture: ComponentFixture<StandModeleEditor>;
  const crud = fakeOf<ReferenceCrudService>({ save: () => Promise.resolve(true) });
  const save = crud.save;

  function mount(locked = false): void {
    TestBed.resetTestingModule();
    save.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideFake(ReferenceDataStore, fakeOf<ReferenceDataStore>({ creneaux: signal([]) })),
        provideFake(ReferenceCrudService, crud),
        { provide: SessionStore, useValue: { lectureSeule: signal(false) } },
      ],
    });
    fixture = TestBed.createComponent(StandModeleEditor);
    fixture.componentRef.setInput('stand', STAND);
    fixture.componentRef.setInput('locked', locked);
    fixture.detectChanges();
  }

  const root = (): HTMLElement => fixture.nativeElement as HTMLElement;
  const button = (label: string): HTMLButtonElement =>
    Array.from(root().querySelectorAll('button')).find((each) =>
      each.textContent!.includes(label),
    )!;

  beforeEach(() => mount());

  it('saves nothing until a rule is typed, then writes the stand with that rule', async () => {
    expect(button('Enregistrer le modèle').disabled).toBe(true);

    button("Ajouter une règle d'horaire").click();
    fixture.detectChanges();
    await fixture.whenStable();
    const line = root().querySelector<HTMLInputElement>('input[name="fenetresLigne0"]')!;
    line.value = '10:00-18:00@2';
    line.dispatchEvent(new Event('input'));
    await fixture.whenStable();
    fixture.detectChanges();
    expect(button('Enregistrer le modèle').disabled).toBe(false);

    await fixture.componentInstance['save']();
    expect(save).toHaveBeenCalledOnce();
    expect(save).toHaveBeenCalledWith(
      'stands',
      expect.objectContaining({
        plages: [],
        horaires: [
          expect.objectContaining({
            fenetres: [expect.objectContaining({ heureDebut: '10:00', effectif: 2 })],
          }),
        ],
      }),
      'S1',
      expect.any(String),
      expect.anything(),
    );
  });

  it('does not write while the stands are frozen or a solve runs', async () => {
    mount(true);
    fixture.componentInstance['change']([]);
    await fixture.componentInstance['save']();
    expect(save).not.toHaveBeenCalled();
  });
});
