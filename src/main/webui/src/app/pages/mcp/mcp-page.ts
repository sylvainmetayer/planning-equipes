import {
  ChangeDetectionStrategy,
  Component,
  OnDestroy,
  OnInit,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { McpApi } from '../../core/api/mcp-api';
import { PromptMcp, StatutMcp } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { StatusMessage } from '../../shared/status-message';
import { NewWindowLink } from '../../shared/new-window-link';

/**
 * How to plug an AI assistant into the application's MCP server (see
 * docs/mcp.md): endpoint, API-key header, and — when the app answers through
 * a Pangolin access proxy (detected via the `X-Pangolin: true` response
 * header on any API call) — the proxy access-token headers that must ride
 * along on every MCP request. Ends with the prompts the server announces,
 * ready to paste for a client that cannot fetch them itself.
 *
 * <h2>The key</h2>
 * The page tells the operator up front whether a key exists at all: without
 * one the MCP server answers 401 to everyone, so configuring a client is
 * wasted effort, and that deserves a warning rather than a silent failure
 * later.
 *
 * Revealing the key — and, if configured server-side, the Pangolin access
 * token id/token alongside it — costs a second admin-password check on top
 * of the session. All three live in component signals and nowhere else — no
 * store, no session storage — so leaving the page destroys them and coming
 * back asks again. A two-minute timer clears them in place for the case the
 * page is left open on screen; copying restarts that timer, since copying is
 * the one interaction that proves somebody is still there.
 */
@Component({
  selector: 'app-mcp-page',
  imports: [
    NewWindowLink,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    StatusMessage,
  ],
  templateUrl: './mcp-page.html',
  styleUrl: './mcp-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class McpPage implements OnInit, OnDestroy {
  /** How long a revealed key stays on screen without being copied. */
  private static readonly EFFACEMENT_MS = 2 * 60 * 1000;

  private readonly mcpApi = inject(McpApi);
  private readonly notifications = inject(NotificationService);

  /** Where this very app is served: the MCP endpoint lives on the same origin. */
  protected readonly origin = window.location.origin;

  /** True when the responses come through a Pangolin access proxy. */
  protected readonly pangolin = signal(false);

  /** Server-side key status; `null` until `/api/mcp/statut` has answered. */
  protected readonly statut = signal<StatutMcp | null>(null);

  /**
   * The prompts the MCP server announces. Empty until `/api/mcp/prompts` has
   * answered — and the section stays hidden rather than showing an empty card,
   * since a page that cannot reach its own API has worse news to give.
   */
  protected readonly prompts = signal<PromptMcp[]>([]);

  protected readonly formulaireOuvert = signal(false);
  protected readonly motDePasse = signal('');
  protected readonly enCours = signal(false);
  protected readonly erreur = signal('');
  protected readonly cle = signal('');
  protected readonly pangolinAccessTokenId = signal('');
  protected readonly pangolinAccessToken = signal('');

  private effacement?: ReturnType<typeof setTimeout>;

  ngOnInit(): void {
    void this.detecterPangolin();
    void this.chargerStatut();
    void this.chargerPrompts();
  }

  ngOnDestroy(): void {
    this.oublierCle();
  }

  // Any same-origin API response carries the proxy's marker header when the
  // deployment sits behind Pangolin; /api/config is the cheapest one to ask.
  private async detecterPangolin(): Promise<void> {
    try {
      const response = await this.mcpApi.configResponse();
      this.pangolin.set(response.headers.get('X-Pangolin') === 'true');
    } catch {
      // Unreachable config endpoint: keep the default, undetected state.
    }
  }

  private async chargerStatut(): Promise<void> {
    try {
      this.statut.set(await this.mcpApi.status());
    } catch {
      // Leave it null: the page then says nothing about the key rather than
      // claiming it is missing, which would be a worse kind of wrong.
    }
  }

  private async chargerPrompts(): Promise<void> {
    try {
      this.prompts.set(await this.mcpApi.prompts());
    } catch {
      // Same choice as the key status: say nothing rather than show a section
      // that looks like "this server has no prompt".
    }
  }

  /** The header the key must travel in, as configured server-side. */
  protected readonly enTeteCle = computed(() => this.statut()?.header ?? 'X-MCP-Api-Key');

  /** Client configuration matching the current deployment, Pangolin headers included when detected. */
  protected readonly configurationClient = computed(() => {
    const headers: Record<string, string> = {
      [this.enTeteCle()]: this.cle() || '<clé PLANNING_MCP_API_KEY>',
    };
    if (this.pangolin()) {
      headers['P-Access-Token-Id'] = this.pangolinAccessTokenId() || '<id du jeton Pangolin>';
      headers['P-Access-Token'] = this.pangolinAccessToken() || '<jeton Pangolin>';
    }
    const config = {
      mcpServers: {
        'planning-equipes': {
          type: 'http',
          url: `${this.origin}/mcp`,
          headers,
        },
      },
    };
    return JSON.stringify(config, null, 2);
  });

  protected ouvrirFormulaire(): void {
    this.formulaireOuvert.set(true);
    this.erreur.set('');
    this.motDePasse.set('');
  }

  protected annuler(): void {
    this.formulaireOuvert.set(false);
    this.motDePasse.set('');
    this.erreur.set('');
  }

  protected async reveler(event: Event): Promise<void> {
    event.preventDefault();
    if (this.enCours() || !this.motDePasse()) {
      return;
    }
    this.enCours.set(true);
    this.erreur.set('');
    try {
      const reponse = await this.mcpApi.regenerateKey(this.motDePasse());
      this.cle.set(reponse.cle);
      this.pangolinAccessTokenId.set(reponse.pangolinAccessTokenId ?? '');
      this.pangolinAccessToken.set(reponse.pangolinAccessToken ?? '');
      this.formulaireOuvert.set(false);
      this.armerEffacement();
    } catch {
      // Deliberately one message for every failure mode: telling a wrong
      // password from a rate-limited one would help exactly the person this
      // second check exists to stop.
      this.erreur.set(
        $localize`:@@mcp.cle.echec:Mot de passe refusé, ou trop de tentatives. Réessayez dans quelques minutes.`,
      );
    } finally {
      this.enCours.set(false);
      this.motDePasse.set('');
    }
  }

  protected oublierCle(): void {
    clearTimeout(this.effacement);
    this.effacement = undefined;
    this.cle.set('');
    this.pangolinAccessTokenId.set('');
    this.pangolinAccessToken.set('');
  }

  private armerEffacement(): void {
    clearTimeout(this.effacement);
    this.effacement = setTimeout(() => this.oublierCle(), McpPage.EFFACEMENT_MS);
  }

  protected async copy(text: string): Promise<void> {
    await navigator.clipboard.writeText(text);
    if (this.cle()) {
      this.armerEffacement();
    }
    this.notifications.notify({
      title: $localize`:@@mcp.copie:Copié dans le presse-papiers.`,
      variant: 'success',
      timeout: 3000,
    });
  }
}
