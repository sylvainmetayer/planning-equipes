// The four documents of Diffuser: each one says who it is for, and what it
// says on the way goes to the page's output panel.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { PlanningApi } from '../../core/api/planning-api';
import { PlanningStateService } from '../../core/planning-state.service';
import { SolverJobService } from '../../core/solver-job.service';
import { DocumentsPanel } from './documents-panel';

type Internals = {
  feuilles: () => Promise<void>;
  archive: () => Promise<void>;
  classeur: () => Promise<void>;
  changements: () => Promise<void>;
};

describe('DocumentsPanel', () => {
  const planningApi = {
    exportFeuilles: vi.fn(),
    exportBundle: vi.fn(),
    exportGlobalPdf: vi.fn(),
    exportPublicationDiff: vi.fn(),
  };
  const planningState = { require: vi.fn() };

  beforeEach(() => {
    Object.values(planningApi).forEach((stub) => stub.mockReset());
    planningState.require.mockReset();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PlanningApi, useValue: planningApi },
        { provide: PlanningStateService, useValue: planningState },
        { provide: SolverJobService, useValue: { editingLocked: () => false } },
      ],
    });
  });

  it('names who each document is for, and never says « Exporter »', async () => {
    const fixture = TestBed.createComponent(DocumentsPanel);
    await fixture.whenStable();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';

    expect(text).toContain('Une feuille par personne');
    expect(text).toContain("L'archive des plannings individuels");
    expect(text).toContain("Le classeur de l'organisateur");
    expect(text).toContain('Le détail des changements');
    expect(text).not.toContain('Exporter');
  });

  it('sends the planning the browser holds for the archive, and reports the outcome', async () => {
    planningState.require.mockResolvedValue({ postes: [] });
    planningApi.exportBundle.mockResolvedValue('Téléchargement démarré.');
    const fixture = TestBed.createComponent(DocumentsPanel);
    const messages: string[] = [];
    fixture.componentInstance.reported.subscribe((message) => messages.push(message));

    await (fixture.componentInstance as unknown as Internals).archive();

    expect(planningApi.exportBundle).toHaveBeenCalledExactlyOnceWith({ postes: [] });
    expect(messages.at(-1)).toBe('Téléchargement démarré.');
  });

  it('reports a refusal rather than staying silent', async () => {
    planningApi.exportGlobalPdf.mockRejectedValue(new Error('Planning vide.'));
    const fixture = TestBed.createComponent(DocumentsPanel);
    const messages: string[] = [];
    fixture.componentInstance.reported.subscribe((message) => messages.push(message));

    await (fixture.componentInstance as unknown as Internals).classeur();

    expect(messages.at(-1)).toContain('Planning vide.');
  });
});
