// The scenario tab of the Imports screen. It uploads a file that replaces the
// whole edition, so what is tested is the path to the shared choreography — the
// one thing that confirms, snapshots and reloads — plus the lock and the two
// outcomes the operator must be able to read: cancelled, and failed.

import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ImportScenarioCard } from './import-scenario-card';

/** A `File` jsdom can read: its own implementation has no `text()`. */
function file(nom: string, contenu: string): File {
  const created = new File([contenu], nom);
  Object.defineProperty(created, 'text', { value: async () => contenu });
  return created;
}

describe('ImportScenarioCard', () => {
  const scenarioImport = { importer: vi.fn(), recapitulatif: vi.fn() };
  const editingLocked = signal(false);

  let fixture: ComponentFixture<ImportScenarioCard>;

  beforeEach(() => {
    editingLocked.set(false);
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
});
