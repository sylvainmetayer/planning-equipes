// The scenario tab of the Imports screen. It uploads a file that replaces the
// whole edition, so what is tested is the path to the shared choreography — the
// one thing that confirms, snapshots and reloads — plus the lock and the two
// outcomes the operator must be able to read: cancelled, and failed.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { EditionsApi } from '../../core/api/editions-api';
import { EtatGel } from '../../core/models';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ImportScenarioCard } from './import-scenario-card';

/** A `File` jsdom can read: its own implementation has no `text()`. */
function file(nom: string, contenu: string): File {
  const created = new File([contenu], nom);
  Object.defineProperty(created, 'text', { value: async () => contenu });
  return created;
}

/** What `GET /api/editions/courant/gel` answers when two families are frozen. */
const FROZEN_STATES: EtatGel[] = [
  { famille: 'STANDS', libelle: 'Stands', fige: true, figeLe: '2026-07-01T08:00:00Z' },
  { famille: 'CRENEAUX', libelle: 'Créneaux', fige: true, figeLe: '2026-07-01T08:00:00Z' },
  { famille: 'TYPOLOGIES_EMPLACEMENTS', libelle: 'Typologies', fige: false, figeLe: null },
  { famille: 'COMPETENCES', libelle: 'Compétences', fige: false, figeLe: null },
];

describe('ImportScenarioCard', () => {
  const scenarioImport = { importer: vi.fn(), recapitulatif: vi.fn() };
  const editingLocked = signal(false);
  const gel = vi.fn<() => Promise<EtatGel[]>>();

  let fixture: ComponentFixture<ImportScenarioCard>;

  beforeEach(() => {
    editingLocked.set(false);
    gel.mockReset();
    gel.mockResolvedValue([]);
    scenarioImport.importer.mockReset();
    scenarioImport.recapitulatif.mockReset();
    scenarioImport.importer.mockResolvedValue({ status: 'imported', result: null });
    scenarioImport.recapitulatif.mockImplementation(
      (_result: unknown, fallback: string) => fallback,
    );

    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: ScenarioImportService, useValue: scenarioImport },
        { provide: SolverJobService, useValue: { editingLocked } },
        { provide: EditionsApi, useValue: { gel } },
      ],
    });
  });

  async function monter(): Promise<HTMLElement> {
    fixture = TestBed.createComponent(ImportScenarioCard);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  async function choisirFichier(racine: HTMLElement, nom = 'festival.yaml'): Promise<void> {
    const input = racine.querySelector('input[type="file"]') as HTMLInputElement;
    Object.defineProperty(input, 'files', {
      configurable: true,
      value: [file(nom, 'festival: {}')],
    });
    input.dispatchEvent(new Event('change'));
    await fixture.whenStable();
  }

  it('sends the validator link to its card of Fichiers › Importer', async () => {
    const racine = await monter();
    const lien = Array.from(racine.querySelectorAll('a')).find((each) =>
      (each.textContent ?? '').includes("Vérifier un fichier sans l'importer"),
    );
    expect(lien?.getAttribute('href')).toBe('/fichiers?cible=verifier');
  });

  it('hands the picked file to the shared import, content and name', async () => {
    const racine = await monter();

    await choisirFichier(racine);

    expect(scenarioImport.importer).toHaveBeenCalledExactlyOnceWith({
      kind: 'file',
      fileName: 'festival.yaml',
      content: 'festival: {}',
    });
    expect(racine.textContent).toContain('festival.yaml');
  });

  it('says nothing when the operator declines the confirmation', async () => {
    scenarioImport.importer.mockResolvedValue({ status: 'cancelled', result: null });
    const racine = await monter();

    await choisirFichier(racine);

    expect(racine.textContent).not.toContain('importé');
  });

  it('reports a refused file rather than looking like it worked', async () => {
    scenarioImport.importer.mockRejectedValue(new Error('Scénario invalide : stands manquants'));
    const racine = await monter();

    await choisirFichier(racine);

    expect(racine.textContent).toContain('stands manquants');
  });

  it('closes the door while a solve holds this edition', async () => {
    const racine = await monter();
    editingLocked.set(true);
    await fixture.whenStable();

    const bouton = Array.from(racine.querySelectorAll('button')).find((each) =>
      each.textContent!.includes('Importer un fichier'),
    ) as HTMLButtonElement;
    expect(bouton.disabled).toBe(true);
  });

  function importButton(racine: HTMLElement): HTMLButtonElement {
    return Array.from(racine.querySelectorAll('button')).find((each) =>
      each.textContent!.includes('Importer un fichier'),
    ) as HTMLButtonElement;
  }

  // The server refuses a scenario into a frozen edition, but the file may name
  // another one: a warning, never a closed door.
  it('warns that this edition is frozen, and still lets a file be picked', async () => {
    gel.mockResolvedValue(FROZEN_STATES);
    const racine = await monter();

    const notice = racine.querySelector('app-gel-edition-notice .gel-edition-notice');
    expect(notice).not.toBeNull();
    expect(notice!.textContent).toContain('« Stands » et « Créneaux »');
    expect(notice!.textContent).toContain('sera refusé jusqu');
    expect(notice!.querySelector('a')!.getAttribute('href')).toContain('/parametres');
    expect(importButton(racine).disabled).toBe(false);

    await choisirFichier(racine);
    expect(scenarioImport.importer).toHaveBeenCalledOnce();
  });

  it('says nothing about the freeze while every family is open', async () => {
    const racine = await monter();

    expect(racine.querySelector('app-gel-edition-notice .gel-edition-notice')).toBeNull();
    expect(importButton(racine).disabled).toBe(false);
  });
});
