import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { MentionsLegales } from '../../core/models';
import { PolitiqueConfidentialitePage } from './politique-confidentialite-page';

const VIDE: MentionsLegales = {
  editeur: '',
  directeurPublication: '',
  hebergeur: '',
  contact: '',
  responsableTraitement: '',
  baseLegale: '',
  conservation: ''
};

function monter(mentions: MentionsLegales) {
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      provideRouter([]),
      { provide: ApiService, useValue: { get: vi.fn(async () => mentions) } }
    ]
  });
  return TestBed.createComponent(PolitiqueConfidentialitePage);
}

describe('PolitiqueConfidentialitePage', () => {
  beforeEach(() => TestBed.resetTestingModule());

  it('nomme les deux outils qui font sortir des données de l’application', async () => {
    // La page l'affirmait autrefois : « aucune donnée cédée à un tiers ».
    // C'était faux dès que la mesure d'audience tournait — un lecteur doit
    // pouvoir savoir qui reçoit quoi.
    const fixture = monter(VIDE);
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    expect(texte).toContain('Cloudflare');
    expect(texte).toContain("l'espace personnel des animateurs sont exclues");
    expect(texte).toContain('Suivi des erreurs');
  });

  it('s’adresse aux mineurs, et dit à quoi sert leur date de naissance', async () => {
    const fixture = monter(VIDE);
    await fixture.whenStable();

    const texte = fixture.nativeElement.textContent as string;
    expect(texte).toContain('Si vous êtes mineur');
    expect(texte).toContain('moins de 18 ans');
    expect(texte).toContain('représentant légal');
  });

  it('signale une base légale ou une durée manquante au lieu de les passer sous silence', async () => {
    const fixture = monter(VIDE);
    await fixture.whenStable();

    const manquants = fixture.nativeElement.querySelectorAll('.mentions-manquant');
    expect(manquants.length).toBeGreaterThanOrEqual(3);
  });

  it('nomme le responsable de traitement, en retombant sur l’éditeur s’il n’est pas distinct', async () => {
    const fixture = monter({ ...VIDE, editeur: 'Association Ludique' });
    await fixture.whenStable();
    expect(fixture.nativeElement.textContent).toContain('Association Ludique');

    TestBed.resetTestingModule();
    const distinct = monter({ ...VIDE, editeur: 'Association Ludique', responsableTraitement: 'Comité Festival' });
    await distinct.whenStable();
    expect(distinct.nativeElement.textContent).toContain('Comité Festival');
  });

  it('dit que le planning est calculé, mais validé par un humain', async () => {
    // Le point qui décide si l'article 22 du RGPD s'applique : une décision
    // « fondée exclusivement » sur un traitement automatisé.
    const fixture = monter(VIDE);
    await fixture.whenStable();

    expect(fixture.nativeElement.textContent).toContain('aide à la décision');
  });
});
