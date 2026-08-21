import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { MentionsLegales } from '../../core/models';
import { MentionsLegalesPage } from './mentions-legales-page';

const VIDE: MentionsLegales = {
  editeur: '',
  directeurPublication: '',
  hebergeur: '',
  contact: '',
  baseLegale: '',
  conservation: ''
};

function monter(mentions: MentionsLegales) {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      { provide: ApiService, useValue: { get: vi.fn(async () => mentions) } }
    ]
  });
  return TestBed.createComponent(MentionsLegalesPage);
}

describe('MentionsLegalesPage', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('dit ce qui manque plutôt que de laisser croire à une mention renseignée', async () => {
    const fixture = monter(VIDE);
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    // L'avertissement global, et le champ par champ : une mention légale
    // incomplète doit se signaler comme telle, jamais passer inaperçue.
    expect(texte).toContain("Aucune information légale n'a été renseignée");
    expect(fixture.nativeElement.querySelectorAll('.mentions-manquant').length).toBeGreaterThan(0);
  });

  it('affiche l’éditeur et l’hébergeur configurés, sans avertissement', async () => {
    const fixture = monter({
      ...VIDE,
      editeur: 'Association Ludique, 12 rue des Dés, 41200 Romorantin',
      hebergeur: 'Hébergeur SAS, Paris',
      contact: 'contact@example.org'
    });
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    expect(texte).toContain('Association Ludique');
    expect(texte).toContain('Hébergeur SAS');
    expect(texte).not.toContain("Aucune information légale n'a été renseignée");
  });

  it('rappelle toujours les droits et l’usage de la date de naissance, quelle que soit la configuration', async () => {
    // Cette partie décrit ce que le logiciel fait vraiment : elle ne dépend
    // d'aucun réglage et doit être lisible même sur un déploiement non configuré.
    const fixture = monter(VIDE);
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    expect(texte).toContain('travail des mineurs');
    expect(texte).toContain('CNIL');
  });
});
