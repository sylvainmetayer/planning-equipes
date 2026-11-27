// The prompts shown here are no longer written in the page: they come from
// the MCP API, which serves what the MCP server itself announces. The
// page used to carry its own copies, and one of them named a tool this
// application has never exposed — so what these tests pin down is that the
// page displays the server's answer, and shows nothing at all rather than an
// empty section when it cannot get one.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { AdminApi } from '../../core/api/admin-api';
import { ApiError } from '../../core/api.service';
import { McpApi } from '../../core/api/mcp-api';
import { NotificationService } from '../../core/notification.service';
import { PromptMcp, StatutMcp } from '../../core/models';
import { McpPage } from './mcp-page';

const PROMPTS: PromptMcp[] = [
  {
    nom: 'construire_la_grille_de_creneaux',
    description: 'Poser une grille de créneaux récurrents.',
    texte: 'Construis la grille de créneaux. 1. diagnostiquer_creneaux…',
  },
  {
    nom: 'diagnostiquer_contraintes_dures',
    description: 'Diagnostiquer les contraintes dures encore non tenues.',
    texte: 'Le dernier planning résolu contient des écarts aux contraintes dures.',
  },
];

describe('McpPage', () => {
  let fixture: ComponentFixture<McpPage>;
  let write: ReturnType<typeof vi.fn>;

  let rotateKey: ReturnType<typeof vi.fn>;
  let regenerateKey: ReturnType<typeof vi.fn>;
  let logout: ReturnType<typeof vi.fn>;
  let assign: ReturnType<typeof vi.fn>;
  let reauthenticate: ReturnType<typeof vi.fn>;

  async function rendre(
    prompts: PromptMcp[] | Error,
    statut: Partial<StatutMcp> = {},
  ): Promise<void> {
    rotateKey = vi.fn(() =>
      Promise.resolve({
        cle: 'cle-generee',
        ancienneExpireLe: null,
        pangolinAccessTokenId: null,
        pangolinAccessToken: null,
      }),
    );
    regenerateKey = vi.fn(() =>
      Promise.resolve({
        cle: 'cle-config',
        pangolinAccessTokenId: null,
        pangolinAccessToken: null,
      }),
    );
    logout = vi.fn(() => Promise.resolve({ urlDeconnexion: null }));
    assign = vi.fn();
    vi.stubGlobal('location', { ...window.location, assign, origin: window.location.origin });
    reauthenticate = vi.fn();
    const mcpApi = {
      reauthenticate,
      rotateKey,
      prompts: vi.fn(() =>
        prompts instanceof Error ? Promise.reject(prompts) : Promise.resolve(prompts),
      ),
      status: vi.fn(() =>
        Promise.resolve({
          configuree: true,
          header: 'X-MCP-Api-Key',
          origine: 'CONFIG',
          oauth: false,
          ...statut,
        }),
      ),
      configResponse: vi.fn(() => Promise.resolve(new Response(null, { status: 200 }))),
      regenerateKey,
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: McpApi, useValue: mcpApi },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
        { provide: AdminApi, useValue: { logout } },
      ],
    });
    fixture = TestBed.createComponent(McpPage);
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function racine(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  beforeEach(() => {
    write = vi.fn(() => Promise.resolve(undefined));
    Object.defineProperty(navigator, 'clipboard', {
      configurable: true,
      value: { writeText: write },
    });
  });

  it('displays one block per prompt served by the MCP server', async () => {
    await rendre(PROMPTS);

    const blocs = racine().querySelectorAll('.mcp-prompt-bloc');
    expect(blocs).toHaveLength(2);
    expect(blocs[0].querySelector('.mcp-prompt-nom')?.textContent).toContain(
      'construire_la_grille_de_creneaux',
    );
    expect(blocs[0].querySelector('.mcp-prompt-description')?.textContent).toContain(
      'Poser une grille de créneaux récurrents.',
    );
    expect(blocs[1].querySelector('.mcp-prompt')?.textContent).toContain(
      'écarts aux contraintes dures',
    );
  });

  it('keeps the order the server gave, which is the order of a real event', async () => {
    await rendre(PROMPTS);

    const noms = Array.from(racine().querySelectorAll('.mcp-prompt-nom')).map((element) =>
      element.textContent?.trim(),
    );
    expect(noms).toEqual(['construire_la_grille_de_creneaux', 'diagnostiquer_contraintes_dures']);
  });

  it('shows no prompt section at all when the endpoint fails', async () => {
    await rendre(new Error('503'));

    expect(racine().querySelectorAll('.mcp-prompt-bloc')).toHaveLength(0);
  });

  it('copies the text of the prompt whose button was pressed', async () => {
    await rendre(PROMPTS);

    const boutons = racine().querySelectorAll<HTMLButtonElement>('.mcp-prompt-bloc button');
    boutons[1].click();
    await fixture.whenStable();

    expect(write).toHaveBeenCalledWith(PROMPTS[1].texte);
  });

  function bouton(texte: string): HTMLButtonElement {
    return Array.from(racine().querySelectorAll<HTMLButtonElement>('button')).find((b) =>
      b.textContent?.includes(texte),
    )!;
  }

  it('does not offer to reveal a generated key, only to renew it', async () => {
    await rendre(PROMPTS, { origine: 'GENERATED' });

    expect(racine().textContent).not.toContain("Révéler la clé d'API");
    expect(racine().textContent).toContain('Générer une nouvelle clé');
  });

  it('generates a key with the chosen overlap, without any password, and shows it', async () => {
    await rendre(PROMPTS);

    bouton('Générer une nouvelle clé').click();
    fixture.detectChanges();
    await fixture.whenStable();
    expect(racine().querySelector('input[type=password]')).toBeNull();
    bouton('Générer et afficher la clé').click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(rotateKey).toHaveBeenCalledWith(15);
    expect(racine().querySelector('.mcp-cle-revelee')?.textContent).toContain('cle-generee');
  });

  it('reveals the configured key in one click', async () => {
    await rendre(PROMPTS);

    bouton("Révéler la clé d'API").click();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(regenerateKey).toHaveBeenCalled();
    expect(racine().querySelector('.mcp-cle-revelee')?.textContent).toContain('cle-config');
  });

  async function refuseForStaleSignIn(mode: string): Promise<void> {
    await rendre(PROMPTS);
    regenerateKey.mockRejectedValueOnce(
      new ApiError(409, 'conflict', 'ancienne', `RECONNEXION_REQUISE_${mode}`),
    );
    bouton("Révéler la clé d'API").click();
    await fixture.whenStable();
    fixture.detectChanges();
  }

  it('asks to sign in again at the provider when the OIDC sign-in is too old', async () => {
    await refuseForStaleSignIn('OIDC');

    expect(racine().textContent).toContain('Votre dernière connexion date de plus de dix minutes');
    expect(racine().querySelector('.mcp-cle-revelee')).toBeNull();
    bouton('Se reconnecter').click();
    await fixture.whenStable();

    expect(reauthenticate).toHaveBeenCalledWith('/parametres?onglet=mcp');
    expect(logout).not.toHaveBeenCalled();
  });

  it('closes a local or break-glass session and goes back to the login page', async () => {
    for (const mode of ['LOCAL', 'BREAK_GLASS']) {
      assign?.mockClear?.();
      await refuseForStaleSignIn(mode);
      bouton('Se reconnecter').click();
      await fixture.whenStable();

      expect(logout).toHaveBeenCalled();
      expect(assign).toHaveBeenCalledWith('/login');
    }
  });

  it('tells about OAuth only when a provider is configured', async () => {
    await rendre(PROMPTS, { oauth: true });
    expect(racine().textContent).toContain("n'a pas besoin de clé");

    await rendre(PROMPTS, { oauth: false });
    expect(racine().textContent).not.toContain("n'a pas besoin de clé");
  });
});
