// The accessibility statement renders what the deployment configured, and says
// plainly what it did not: claiming a compliance nobody measured would be
// worse than an empty statement.

import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { DeclarationAccessibilite, MentionsLegales } from '../../core/models';
import { DeclarationAccessibilitePage } from './declaration-accessibilite-page';

function mentions(accessibilite: Partial<DeclarationAccessibilite> = {}): MentionsLegales {
  return {
    editeur: '',
    directeurPublication: '',
    hebergeur: '',
    contact: '',
    responsableTraitement: '',
    baseLegale: '',
    conservation: '',
    mesureAudience: false,
    suiviErreurs: false,
    accessibilite: {
      etat: '',
      dateAudit: '',
      contenusNonAccessibles: '',
      signalement: '',
      ...accessibilite,
    },
  };
}

async function render(valeur: MentionsLegales): Promise<HTMLElement> {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: ApiService, useValue: { get: vi.fn(async () => valeur) } },
    ],
  });
  const fixture = TestBed.createComponent(DeclarationAccessibilitePage);
  await fixture.whenStable();
  return fixture.nativeElement as HTMLElement;
}

function text(root: HTMLElement): string {
  return root.textContent!.replace(/\s+/g, ' ');
}

describe('DeclarationAccessibilitePage', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('says the statement is not filled in rather than claiming a compliance', async () => {
    const root = await render(mentions());

    expect(text(root)).toContain('aucun audit n');
    expect(text(root)).not.toContain('en conformité');
    expect(root.querySelectorAll('h1')).toHaveLength(1);
  });

  it('states the configured compliance, its audit and where to report a barrier', async () => {
    const root = await render(
      mentions({
        etat: 'partielle',
        dateAudit: '18 septembre 2026',
        contenusNonAccessibles: 'Les PDF téléchargés.',
        signalement: 'accessibilite@example.org',
      }),
    );

    expect(text(root)).toContain('en conformité partielle');
    expect(text(root)).toContain('RGAA');
    expect(text(root)).toContain('18 septembre 2026');
    expect(text(root)).toContain('Les PDF téléchargés.');
    expect(text(root)).toContain('accessibilite@example.org');
    expect(text(root)).toContain('Défenseur des droits');
  });
});
