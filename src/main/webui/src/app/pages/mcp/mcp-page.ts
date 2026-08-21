import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ApiService } from '../../core/api.service';
import { CleMcp, StatutMcp } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { StatusMessage } from '../../shared/status-message';

/**
 * How to plug an AI assistant into the application's MCP server (see
 * docs/mcp.md): endpoint, API-key header, and — when the app answers through
 * a Pangolin access proxy (detected via the `X-Pangolin: true` response
 * header on any API call) — the proxy access-token headers that must ride
 * along on every MCP request. Ends with ready-to-paste prompts naming the
 * server, for the two most common needs.
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
  imports: [FormsModule, MatButtonModule, MatCardModule, MatFormFieldModule, MatIconModule, MatInputModule,
    StatusMessage],
  templateUrl: './mcp-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class McpPage implements OnDestroy {
  /** How long a revealed key stays on screen without being copied. */
  private static readonly EFFACEMENT_MS = 2 * 60 * 1000;

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);

  /** Where this very app is served: the MCP endpoint lives on the same origin. */
  protected readonly origin = window.location.origin;

  /** True when the responses come through a Pangolin access proxy. */
  protected readonly pangolin = signal(false);

  /** Server-side key status; `null` until `/api/mcp/statut` has answered. */
  protected readonly statut = signal<StatutMcp | null>(null);

  protected readonly formulaireOuvert = signal(false);
  protected readonly motDePasse = signal('');
  protected readonly enCours = signal(false);
  protected readonly erreur = signal('');
  protected readonly cle = signal('');
  protected readonly pangolinAccessTokenId = signal('');
  protected readonly pangolinAccessToken = signal('');

  private effacement?: ReturnType<typeof setTimeout>;

  constructor() {
    void this.detecterPangolin();
    void this.chargerStatut();
  }

  ngOnDestroy(): void {
    this.oublierCle();
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

  private async chargerStatut(): Promise<void> {
    try {
      this.statut.set(await this.api.get<StatutMcp>('/api/mcp/statut'));
    } catch {
      // Leave it null: the page then says nothing about the key rather than
      // claiming it is missing, which would be a worse kind of wrong.
    }
  }

  /** The header the key must travel in, as configured server-side. */
  protected readonly enTeteCle = computed(() => this.statut()?.header ?? 'X-MCP-Api-Key');

  /** Client configuration matching the current deployment, Pangolin headers included when detected. */
  protected readonly configurationClient = computed(() => {
    const headers: Record<string, string> = {
      [this.enTeteCle()]: this.cle() || '<clé PLANNING_MCP_API_KEY>'
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
          headers
        }
      }
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
      const reponse = await this.api.post<CleMcp>('/api/mcp/cle', { motDePasse: this.motDePasse() });
      this.cle.set(reponse.cle);
      this.pangolinAccessTokenId.set(reponse.pangolinAccessTokenId ?? '');
      this.pangolinAccessToken.set(reponse.pangolinAccessToken ?? '');
      this.formulaireOuvert.set(false);
      this.armerEffacement();
    } catch {
      // Deliberately one message for every failure mode: telling a wrong
      // password from a rate-limited one would help exactly the person this
      // second check exists to stop.
      this.erreur.set($localize`:@@mcp.cle.echec:Mot de passe refusé, ou trop de tentatives. Réessayez dans quelques minutes.`);
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

  protected readonly promptExemple = $localize`:@@mcp.prompt.texte:Utilise le serveur MCP « planning-equipes » : le dernier planning résolu contient des violations de contraintes dures. Récupère le diagnostic de la dernière analyse (lance une analyse via lancer_analyse si aucune n'est disponible), puis liste chaque contrainte HARD en défaut avec son nombre de correspondances. Pour chacune : identifie les postes et créneaux touchés (expliquer_affectation sur les postes concernés), donne la cause racine probable (manque de compétences sur la typologie, indisponibilité, effectif insuffisant sur la tranche horaire, plafond légal d'heures atteint, aucun animateur polyvalent…), et dis-moi précisément ce que je dois modifier dans les données de référence pour la corriger (ajouter des disponibilités, ajuster l'effectif minimum du stand, revoir les amplitudes, désigner une typologie ninja, désactiver temporairement une contrainte…). Termine par une liste d'actions concrètes classées par impact décroissant, sans exposer de données nominatives inutiles.`;

  protected readonly promptGrille = $localize`:@@mcp.prompt.grille.texte:Utilise le serveur MCP « planning-equipes » pour construire la grille de créneaux de l'édition. Commence par diagnostiquer_grille_creneaux pour voir ce qui existe déjà, et demande-moi si la grille doit être en AMPLITUDES (journées à découper en vacations) ou en VACATIONS (vacations finales) avant d'écrire quoi que ce soit. Utilise ensuite previsualiser_creneaux_recurrents pour me montrer ce que ta règle produirait, et n'appelle creer_creneaux_recurrents qu'après mon accord. Termine par valider_creneaux et explique-moi chaque anomalie remontée — doublon, chevauchement, trou dans une journée, date isolée, stand que personne ne pourra armer, sous-effectif — en me disant pour chacune si c'est une vraie erreur ou un choix légitime de ma part.`;

  protected async copier(texte: string): Promise<void> {
    await navigator.clipboard.writeText(texte);
    if (this.cle()) {
      this.armerEffacement();
    }
    this.notifications.notify({
      title: $localize`:@@mcp.copie:Copié dans le presse-papiers.`,
      variant: 'success',
      timeout: 3000
    });
  }
}
