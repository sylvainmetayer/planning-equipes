// The day-template form: the line is read before anything leaves, a typo is
// named against the field, and the payload is what the service receives.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { JourneesTypesApi } from '../../core/api/journees-types-api';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SolverJobService } from '../../core/solver-job.service';
import { JourneeType } from '../../core/models';
import { JourneeTypeDialog } from './journee-type-dialog';

function monter(journeeType: JourneeType | null) {
  const create = vi.fn(async (payload: JourneeType) => ({ ...payload, id: 9 }));
  const update = vi.fn(async (id: number, payload: JourneeType) => ({ ...payload, id }));
  const close = vi.fn();
  TestBed.resetTestingModule();
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: SolverJobService, useValue: { editingLocked: signal(false) } },
      { provide: JourneesTypesApi, useValue: { create, update } },
      { provide: ReferenceCrudService, useValue: { reportError: vi.fn() } },
      { provide: MatDialogRef, useValue: { close } },
      { provide: MAT_DIALOG_DATA, useValue: { journeeType } },
    ],
  });
  return { fixture: TestBed.createComponent(JourneeTypeDialog), create, update, close };
}

function racine(fixture: ComponentFixture<JourneeTypeDialog>): HTMLElement {
  return fixture.nativeElement as HTMLElement;
}

function saisir(fixture: ComponentFixture<JourneeTypeDialog>, name: string, valeur: string): void {
  const input = racine(fixture).querySelector(`input[name="${name}"]`) as HTMLInputElement;
  input.value = valeur;
  input.dispatchEvent(new Event('input'));
}

function submit(fixture: ComponentFixture<JourneeTypeDialog>): void {
  racine(fixture).querySelector('form')!.dispatchEvent(new Event('submit'));
}

describe('JourneeTypeDialog', () => {
  beforeEach(() => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
  });

  it('lays the timeslots once without keeping a template, no name needed', async () => {
    const { fixture, create, close } = monter(null);
    await fixture.whenStable();

    saisir(fixture, 'vacations', '9h-12h, 14:00-20:00');
    await fixture.whenStable();
    const bouton = Array.from(racine(fixture).querySelectorAll('button')).find((each) =>
      each.textContent!.includes('Appliquer sans mémoriser'),
    ) as HTMLButtonElement;
    expect(bouton.disabled).toBe(false);
    bouton.click();

    expect(create).not.toHaveBeenCalled();
    expect(close).toHaveBeenCalledWith({ fenetres: '09:00-12:00, 14:00-20:00' });
  });

  it('creates a template from the name and the line, relay marker included', async () => {
    const { fixture, create, close } = monter(null);
    await fixture.whenStable();

    saisir(fixture, 'nom', 'Jour normal');
    saisir(fixture, 'vacations', '09:00-12:00, 12:00-13:00 R, 14:00-20:00');
    await fixture.whenStable();
    expect(racine(fixture).querySelectorAll('.vacation-chip')).toHaveLength(3);
    expect(racine(fixture).querySelectorAll('.vacation-chip-relais')).toHaveLength(1);

    submit(fixture);
    await fixture.whenStable();

    expect(create).toHaveBeenCalledOnce();
    expect(create.mock.calls[0][0]).toEqual({
      nom: 'Jour normal',
      vacations: [
        { heureDebut: '09:00', heureFin: '12:00', couverturePause: false },
        { heureDebut: '12:00', heureFin: '13:00', couverturePause: true },
        { heureDebut: '14:00', heureFin: '20:00', couverturePause: false },
      ],
      modifieLe: null,
    });
    expect(close).toHaveBeenCalledWith(expect.objectContaining({ id: 9 }));
  });

  it('names a piece it cannot read and keeps the submit disabled', async () => {
    const { fixture, create } = monter(null);
    await fixture.whenStable();

    saisir(fixture, 'nom', 'Nocturne');
    saisir(fixture, 'vacations', '14:00-20:00, 20:00-');
    await fixture.whenStable();

    expect(racine(fixture).querySelector('.field-error')!.textContent).toContain('20:00-');
    expect(
      (racine(fixture).querySelector('button[type="submit"]') as HTMLButtonElement).disabled,
    ).toBe(true);
    submit(fixture);
    await fixture.whenStable();
    expect(create).not.toHaveBeenCalled();
  });

  it('opens on a template with its line, and updates it under its write stamp', async () => {
    const { fixture, update } = monter({
      id: 4,
      nom: 'Nocturne',
      modifieLe: '2026-09-12T10:00:00Z',
      vacations: [{ heureDebut: '14:00:00', heureFin: '20:00:00', couverturePause: false }],
    });
    await fixture.whenStable();

    expect(
      (racine(fixture).querySelector('input[name="vacations"]') as HTMLInputElement).value,
    ).toBe('14:00-20:00');
    saisir(fixture, 'vacations', '14:00-20:00, 20:00-00:00');
    await fixture.whenStable();
    submit(fixture);
    await fixture.whenStable();

    expect(update).toHaveBeenCalledWith(
      4,
      expect.objectContaining({ nom: 'Nocturne', modifieLe: '2026-09-12T10:00:00Z' }),
    );
    expect(update.mock.calls[0][1].vacations).toHaveLength(2);
  });
});
