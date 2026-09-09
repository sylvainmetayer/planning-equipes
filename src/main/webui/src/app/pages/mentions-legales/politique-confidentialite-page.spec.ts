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
  conservation: '',
  mesureAudience: false,
  suiviErreurs: false
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
    const fixture = monter({ ...VIDE, mesureAudience: true, suiviErreurs: true });
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Cloudflare');
    expect(text).toContain("l'espace personnel des animateurs sont exclues");
    expect(text).toContain('Suivi des erreurs');
    expect(text).toContain('Ces deux traitements');
    expect(text).toContain('les outils techniques décrits ci-dessous');
    expect(text).toContain("Mesure d'audience et suivi technique");
  });

  it('ne nomme aucun outil tiers quand le déploiement n’en fait tourner aucun', async () => {
    // Les deux briques se désactivent par variable vide, et c'est le défaut.
    // Annoncer un transfert hors UE qui n'a pas lieu est faux dans le sens le
    // moins grave, mais un lecteur qui prend en défaut la seule affirmation
    // qu'il peut vérifier n'a plus de raison de croire les autres.
    const fixture = monter(VIDE);
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).not.toContain('Cloudflare');
    expect(text).not.toContain('Bugsink');
    expect(text).not.toContain("Mesure d'audience");
    expect(text).not.toContain('décrits ci-dessous');
    // Les sous-traitants qui restent, eux, sont toujours là.
    expect(text).toContain("le service d'envoi des e-mails");
  });

  it('ne décrit que l’outil actif, et accorde le titre comme le paragraphe d’opposition', async () => {
    // Le titre et le renvoi « décrits ci-dessous » sont écrits pour deux
    // outils : les accrocher au fait qu'il y en ait *un* annoncerait une
    // mesure d'audience au-dessus d'une liste qui ne contient que le suivi
    // des erreurs — la même affirmation sans objet, un outil plus tard.
    const audience = monter({ ...VIDE, mesureAudience: true });
    await audience.whenStable();

    const texteAudience = audience.nativeElement.textContent as string;
    expect(texteAudience).toContain('Cloudflare');
    expect(texteAudience).not.toContain('Bugsink');
    expect(texteAudience).toContain('Ce traitement repose');
    expect(texteAudience).not.toContain('Ces deux traitements');
    expect(texteAudience).not.toContain('suivi technique');
    expect(texteAudience).toContain("l'outil technique décrit ci-dessous");

    TestBed.resetTestingModule();
    const erreurs = monter({ ...VIDE, suiviErreurs: true });
    await erreurs.whenStable();

    const texteErreurs = erreurs.nativeElement.textContent as string;
    expect(texteErreurs).toContain('Bugsink');
    expect(texteErreurs).not.toContain('Cloudflare');
    expect(texteErreurs).toContain('Ce traitement repose');
    expect(texteErreurs).not.toContain("Mesure d'audience");
    expect(texteErreurs).toContain("l'outil technique décrit ci-dessous");
  });

  it('s’adresse aux mineurs, et dit à quoi sert leur date de naissance', async () => {
    const fixture = monter(VIDE);
    await fixture.whenStable();

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Si vous êtes mineur');
    expect(text).toContain('moins de 18 ans');
    expect(text).toContain('représentant légal');
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
