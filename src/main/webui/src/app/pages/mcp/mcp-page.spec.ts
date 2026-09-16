// The prompts shown here are no longer written in the page: they come from
// the MCP API, which serves what the MCP server itself announces. The
// page used to carry its own copies, and one of them named a tool this
// application has never exposed — so what these tests pin down is that the
// page displays the server's answer, and shows nothing at all rather than an
// empty section when it cannot get one.
//
// The free-offering card is prose except for one line: the warning that Le
// Chat can only present the key in `Authorization`, which an access proxy
// often consumes. It is worth a test because it is the one piece that must
// appear on some deployments and not on others.

import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { McpApi } from '../../core/api/mcp-api';
import { NotificationService } from '../../core/notification.service';
import { PromptMcp } from '../../core/models';
import { McpPage } from './mcp-page';

const PROMPTS: PromptMcp[] = [
  {
    nom: 'construire_la_grille_de_creneaux',
    description: 'Poser une grille de créneaux récurrents.',
    texte: 'Construis la grille de créneaux. 1. diagnostiquer_grille_creneaux…',
  },
  {
    nom: 'diagnostiquer_contraintes_dures',
    description: 'Diagnostiquer les contraintes dures encore violées.',
    texte: 'Le dernier planning résolu contient des violations de contraintes dures.',
  },
];

describe('McpPage', () => {
  let fixture: ComponentFixture<McpPage>;
  let write: ReturnType<typeof vi.fn>;

  async function rendre(prompts: PromptMcp[] | Error, pangolin = false): Promise<void> {
    const mcpApi = {
      prompts: vi.fn(async () => {
        if (prompts instanceof Error) {
          throw prompts;
        }
        return prompts;
      }),
      status: vi.fn(async () => ({ configuree: true, header: 'X-MCP-Api-Key' })),
      configResponse: vi.fn(
        async () =>
          new Response(null, {
            status: 200,
            headers: pangolin ? { 'X-Pangolin': 'true' } : {},
          }),
      ),
      regenerateKey: vi.fn(),
    };
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: McpApi, useValue: mcpApi },
        { provide: NotificationService, useValue: { notify: vi.fn() } },
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
    write = vi.fn(async () => undefined);
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
      'violations de contraintes dures',
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

  it('warns that the Bearer header may not survive the proxy, behind one', async () => {
    await rendre(PROMPTS, true);

    expect(racine().textContent).toContain('Le Chat ne présente la clé');
  });

  it('says nothing about the Bearer header when no proxy answers', async () => {
    await rendre(PROMPTS);

    expect(racine().textContent).not.toContain('Le Chat ne présente la clé');
  });

  it('copies the text of the prompt whose button was pressed', async () => {
    await rendre(PROMPTS);

    const boutons = racine().querySelectorAll<HTMLButtonElement>('.mcp-prompt-bloc button');
    boutons[1].click();
    await fixture.whenStable();

    expect(write).toHaveBeenCalledWith(PROMPTS[1].texte);
  });
});
