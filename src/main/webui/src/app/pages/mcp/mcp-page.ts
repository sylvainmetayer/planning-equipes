import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { ApiService } from '../../core/api.service';
import { NotificationService } from '../../core/notification.service';
import { StatusMessage } from '../../shared/status-message';

/**
 * How to plug an AI assistant into the application's MCP server (see
 * docs/mcp.md): endpoint, API-key header, and — when the app answers through
 * a Pangolin access proxy (detected via the `X-Pangolin: true` response
 * header on any API call) — the proxy access-token headers that must ride
 * along on every MCP request. Ends with a ready-to-paste prompt for the most
 * common need: debugging a solution that carries hard-constraint violations.
 */
@Component({
  selector: 'app-mcp-page',
  imports: [MatCardModule, MatButtonModule, MatIconModule, StatusMessage],
  templateUrl: './mcp-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class McpPage {
  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);

  /** Where this very app is served: the MCP endpoint lives on the same origin. */
  protected readonly origin = window.location.origin;

  /** True when the responses come through a Pangolin access proxy. */
  protected readonly pangolin = signal(false);

  constructor() {
    void this.detecterPangolin();
  }

  // Any same-origin API response carries the proxy's marker header when the
  // deployment sits behind Pangolin; /api/config is the cheapest one to ask.
  private async detecterPangolin(): Promise<void> {
    try {
      const response = await this.api.getResponse<unknown>('/api/config');
      this.pangolin.set(response.headers.get('X-Pangolin') === 'true');
    } catch {
      // Unreachable config endpoint: keep the default, undetected state.
    }
  }

  /** Client configuration matching the current deployment, Pangolin headers included when detected. */
  protected readonly configurationClient = computed(() => {
    const headers: Record<string, string> = { 'X-MCP-Api-Key': '<clé PLANNING_MCP_API_KEY>' };
    if (this.pangolin()) {
      headers['P-Access-Token-Id'] = '<id du jeton Pangolin>';
      headers['P-Access-Token'] = '<jeton Pangolin>';
    }
    const config = {
      mcpServers: {
        'planning-equipes': {
          type: 'http',
          url: `${this.origin}/mcp`,
          headers
        }
      }
    };
    return JSON.stringify(config, null, 2);
  });

  protected readonly promptExemple = $localize`:@@mcp.prompt.texte:Le dernier planning résolu contient des violations de contraintes dures. Récupère le diagnostic de la dernière analyse (lance une analyse via lancer_analyse si aucune n'est disponible), puis liste chaque contrainte HARD en défaut avec son nombre de correspondances. Pour chacune : identifie les postes et créneaux touchés (expliquer_score_poste sur les postes concernés), donne la cause racine probable (manque de compétences sur la typologie, indisponibilité, effectif insuffisant sur la tranche horaire, plafond légal d'heures atteint, aucun animateur polyvalent…), et dis-moi précisément ce que je dois modifier dans les données de référence pour la corriger (ajouter des disponibilités, ajuster l'effectif minimum du stand, revoir les amplitudes, désigner une typologie ninja, désactiver temporairement une contrainte…). Termine par une liste d'actions concrètes classées par impact décroissant, sans exposer de données nominatives inutiles.`;

  protected async copier(texte: string): Promise<void> {
    await navigator.clipboard.writeText(texte);
    this.notifications.notify({
      title: $localize`:@@mcp.copie:Copié dans le presse-papiers.`,
      variant: 'success',
      timeout: 3000
    });
  }
}
