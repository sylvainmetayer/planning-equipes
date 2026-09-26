// The two tabs of the Débogage page: where each card lives, what the address
// says, and the case a tab group gets wrong — a page that reads its query
// param once and never again. What moved away (the scenarios, the validator,
// the reset, the simulated clock) must not be here any more.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, ParamMap } from '@angular/router';
import { BehaviorSubject } from 'rxjs';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { DebugPage } from './debug-page';

describe('DebugPage — onglets', () => {
  let fixture: ComponentFixture<DebugPage>;
  let params: BehaviorSubject<ParamMap>;

  async function rendre(options: { onglet?: string } = {}): Promise<void> {
    params = new BehaviorSubject<ParamMap>(
      convertToParamMap(options.onglet ? { onglet: options.onglet } : {}),
    );
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: ApiService,
          useValue: {
            get: vi.fn(async () => ({ adminEmail: null })),
            post: vi.fn(),
          },
        },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: params.value }, queryParamMap: params },
        },
      ],
    });
    fixture = TestBed.createComponent(DebugPage);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  function textOf(): string {
    return racine().textContent!.replace(/\s+/g, ' ');
  }

  /** The tab buttons, by the label a reader sees — the icon's ligature text apart. */
  function onglets(): string[] {
    return Array.from(racine().querySelectorAll('mat-button-toggle')).map((each) =>
      Array.from(each.querySelectorAll('mat-icon'))
        .reduce((libelle, icone) => libelle.replace(icone.textContent!, ''), each.textContent!)
        .trim(),
    );
  }

  async function cliquerOnglet(nom: string): Promise<void> {
    const onglet = Array.from(racine().querySelectorAll('mat-button-toggle')).find((each) =>
      each.textContent!.includes(nom),
    );
    expect(onglet, `onglet « ${nom} » absent`).toBeDefined();
    (onglet as HTMLElement).querySelector('button')!.click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  beforeEach(async () => {
    await rendre();
  });

  it('names its two tabs and opens on the raw analysis', () => {
    expect(onglets()).toEqual(['Résolution', 'Vérifications']);
    expect(textOf()).toContain('Dernière analyse');
    expect(textOf()).toContain("Documentation de l'API");
    expect(textOf()).not.toContain('Envoyer un mail de test');
  });

  it('keeps the raw checks on the Vérifications tab, pgAdmin included', async () => {
    await cliquerOnglet('Vérifications');
    expect(textOf()).toContain('Envoyer un mail de test');
    expect(textOf()).toContain('Mailpit');
    expect(textOf()).toContain('pgAdmin');
  });

  /** Each has a screen of its own now: Fichiers, Éditions, Paramètres › Instance. */
  it('no longer carries the gestures of an organiser or an operator', async () => {
    for (const nom of ['Résolution', 'Vérifications']) {
      await cliquerOnglet(nom);
      expect(textOf()).not.toContain('Vider');
      expect(textOf()).not.toContain('Scénarios');
      expect(textOf()).not.toContain('Validateur YAML');
      expect(racine().querySelector('input#date-du-jour')).toBeNull();
    }
  });

  /**
   * The router reuses this component when one navigates to `/debug` again with
   * another `onglet` — from the palette. Read once in the constructor, the
   * address changed and the screen did not, until an F5.
   */
  it('follows the address instead of reading it once', async () => {
    params.next(convertToParamMap({ onglet: 'verifications' }));
    await fixture.whenStable();
    fixture.detectChanges();

    expect(textOf()).toContain('Envoyer un mail de test');
  });

  it('opens on the default tab when the address names one it does not know', async () => {
    await rendre({ onglet: 'validateur-yaml' });

    expect(textOf()).toContain('Dernière analyse');
  });

  /** The output panel answers an action of one tab and is read after it. */
  it('keeps the output panel under every tab', async () => {
    expect(racine().querySelector('app-output-panel')).not.toBeNull();

    await cliquerOnglet('Vérifications');
    expect(racine().querySelector('app-output-panel')).not.toBeNull();
  });
});
