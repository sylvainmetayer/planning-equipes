import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, Location } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { reglagesAvances } from '../../core/reglages-avances';
import { ReglagesAvancesToggle } from '../../shared/reglages-avances';
import { HistoriquePage } from '../historique/historique-page';
import { McpPage } from '../mcp/mcp-page';
import { AdminApi } from '../../core/api/admin-api';
import { EditionsApi } from '../../core/api/editions-api';
import { EditionStore } from '../../core/edition.store';
import { ProblemesStore } from '../../core/problemes.store';
import { EtatSauvegarde } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { OutputPanel } from '../../shared/output-panel';
import { AffichageMuralLinks } from './affichage-mural-links';
import { ComptesCard } from './comptes-card';
import { SessionStore } from '../../core/session.store';
import { ContactOrganisationCard } from './contact-organisation-card';
import { ReglageResponsablesCard } from './reglage-responsables-card';
import { GuichetsCard } from './guichets-card';
import { ParametresNotificationsPanel } from './parametres-notifications';
import { ParametresMeteoPanel } from './parametres-meteo';
import { errorPrefix } from '../../core/error-message';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { ONGLETS_ADMIN, OngletParametres, readOngletParametres } from './parametres';
import { SingleKeyShortcutsToggle } from '../../shared/single-key-shortcuts-toggle';
import { GelReferentielCard } from '../../shared/gel-referentiel-card';
import { HorlogeSimuleeCard } from './horloge-simulee-card';
import { WebhooksCard } from './webhooks-card';
import { TODAY_ANCHOR } from '../../core/date-mock.service';

/**
 * The settings page, three tabs carried by `?onglet=` (issue #720), the same
 * mechanics as Diagnostic and Imports — a toggle group, the default tab
 * writing nothing:
 *
 * - « Édition » (default) — what the `X-Edition-Id` partition scopes and no
 *   rule reads: the edition's name, the freeze of its referential (the only
 *   place it is edited), its guichets — the collection, the foire, the
 *   covoiturage, each opened and closed here with its dates —, the e-mails it
 *   sends of itself (`#emails`), and the organisation's contact shown in the
 *   espace animateur. What decides the plan — legal parameters, quality
 *   thresholds, weights, the solve budget, the polyvalent typologie — is « Règles
 *   du planning ».
 * - « Affichage mural » — the links that open the control room's television
 *   without an admin session (ADR 0053): per edition, created and revoked here.
 * - « Instance » — what the operator configured and what holds for the whole
 *   database, every edition included: the nightly backup, the single-key shortcuts of this browser, and the simulated
 *   clock where the server allows one (a demonstration or staging server).
 *
 * The feasibility banner stays above the tabs: it speaks of the edition, not
 * of a tab. So does the output panel — an error answered on one tab survives
 * a move to another.
 */
@Component({
  selector: 'app-parametres-page',
  imports: [
    ComptesCard,
    ReglagesAvancesToggle,
    AffichageMuralLinks,
    HistoriquePage,
    McpPage,
    ContactOrganisationCard,
    ReglageResponsablesCard,
    GuichetsCard,
    SingleKeyShortcutsToggle,
    DatePipe,
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatButtonToggleModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSlideToggleModule,
    MatTooltipModule,
    RouterLink,
    FeasibilityBanner,
    OutputPanel,
    ParametresNotificationsPanel,
    ParametresMeteoPanel,
    GelReferentielCard,
    HorlogeSimuleeCard,
    WebhooksCard,
  ],
  templateUrl: './parametres-page.html',
  styleUrl: './parametres.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresPage implements OnInit {
  protected readonly output = signal('');
  /** Pre-solve diagnostic shown by the banner at the top of the page. */
  protected readonly problemes = inject(ProblemesStore);

  private readonly adminApi = inject(AdminApi);
  private readonly notifications = inject(NotificationService);
  private readonly editions = inject(EditionStore);
  private readonly editionsApi = inject(EditionsApi);

  /**
   * What the operator configured for the whole database, the journal and the
   * AI assistant's access: their tabs show under « Réglages avancés », or
   * when the address names one.
   */
  protected readonly avance = reglagesAvances();

  protected readonly session = inject(SessionStore);

  protected readonly onglet = signal<OngletParametres>('edition');

  /* ---------------------------- The edition's name --------------------------- */

  protected readonly edition = this.editions.courant;
  protected readonly nomDraft = signal<string | null>(null);
  protected readonly nom = computed(() => this.nomDraft() ?? this.edition()?.nom ?? '');
  protected readonly nomModifie = computed(() => {
    const nom = this.nomDraft()?.trim();
    return !!nom && nom !== this.edition()?.nom;
  });
  protected readonly renommage = signal(false);

  private readonly route = inject(ActivatedRoute);
  private readonly location = inject(Location);

  constructor() {
    // Followed rather than read once, like Diagnostic: clicking « Paramètres »
    // in the menu from `/parametres?onglet=instance` navigates to this very
    // route with another `onglet`, and the router reuses the component instead
    // of building it again. `replaceState` (ADR 0018) emits nothing here, so
    // the effect below cannot feed this subscription.
    this.route.queryParamMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      // `?focus=date-du-jour` names the simulated clock's field, which lives
      // on the Instance tab: the toolbar indicator's link lands on it even
      // when it names no tab.
      const demande = params.get('onglet');
      this.onglet.set(
        demande === null && params.get('focus') === TODAY_ANCHOR
          ? 'instance'
          : readOngletParametres(demande),
      );
    });
    // The Historique tab writes `onglet` itself — `historique` or its own
    // `connexions` —: two writers on one key would race on arrival.
    // The tabs of the instance, the accounts and the MCP key are the
    // administrators': an organiser landing on one by its address, or one who
    // just lost the right, is brought back to the edition's own.
    effect(() => {
      if (
        this.session.known() &&
        !this.session.isAdmin() &&
        ONGLETS_ADMIN.includes(this.onglet())
      ) {
        this.onglet.set('edition');
      }
    });
    keepViewInQueryParams(() =>
      this.onglet() === 'historique'
        ? {}
        : { onglet: this.onglet() === 'edition' ? null : this.onglet() },
    );
    void this.problemes.reloadFeasibility();
    // `#guichets`, `#emails`, `#contact`: the card a link named — the former
    // `?onglet=emails` lands on its section. Programmatic, like the help's
    // summary: what scrolls in this shell is the sidenav content, and the
    // `<base href>` would resolve a bare fragment against the root.
    const ancre = this.route.snapshot.fragment;
    if (ancre) {
      afterNextRender(() => document.getElementById(ancre)?.scrollIntoView?.({ block: 'start' }));
    }
  }

  ngOnInit(): void {
    // The backup state is the administrators' (`/api/backups`).
    void this.session
      .ready()
      .then(() => (this.session.isAdmin() ? this.chargerSauvegarde() : undefined));
  }

  protected changerOnglet(onglet: OngletParametres): void {
    // The Historique tab's own keys (`?date=`, `?type=`…) mean nothing to the
    // others: the address is reset when entering or leaving it.
    if (onglet !== this.onglet() && (onglet === 'historique' || this.onglet() === 'historique')) {
      const [chemin] = this.location.path().split('?');
      this.location.replaceState(chemin || '/');
    }
    this.onglet.set(onglet);
  }

  /**
   * Renames the current edition, as the Éditions page does: an empty or
   * unchanged name writes nothing. The switcher and the page title follow once
   * the store has re-read the editions.
   */
  protected async renommer(): Promise<void> {
    const edition = this.edition();
    const nom = this.nomDraft()?.trim();
    if (!edition || !nom || nom === edition.nom) {
      return;
    }
    this.renommage.set(true);
    try {
      await this.editionsApi.rename(edition.id, nom);
      await this.editions.reload();
      this.nomDraft.set(null);
      this.notifications.notify({
        title: $localize`:@@parametres.nom.saved:Édition renommée`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.renommage.set(false);
    }
  }

  /* --------------------------- Sauvegarde automatique ------------------------- */

  protected readonly sauvegarde = signal<EtatSauvegarde | null>(null);
  protected readonly sauvegardeBusy = signal(false);

  /**
   * The dumps, newest first, as the server listed them. Kept as a computed so
   * the template never has to guard `sauvegarde()` being null twice.
   */
  protected readonly fichiersSauvegarde = computed(() => this.sauvegarde()?.files ?? []);

  private async chargerSauvegarde(): Promise<void> {
    try {
      this.sauvegarde.set(await this.adminApi.backups());
    } catch {
      // A settings screen that fails to load as a whole because one card could
      // not be read would be a worse outcome than that card staying absent.
      this.sauvegarde.set(null);
    }
  }

  protected async basculerSauvegarde(actif: boolean): Promise<void> {
    this.sauvegardeBusy.set(true);
    try {
      this.sauvegarde.set(await this.adminApi.setBackupsActive(actif));
      this.notifications.notify({
        title: actif
          ? $localize`:@@parametres.sauvegarde.reprise:Sauvegarde automatique réactivée`
          : $localize`:@@parametres.sauvegarde.suspendue:Sauvegarde automatique suspendue`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.sauvegardeBusy.set(false);
    }
  }

  /** Human-sized file length; a dump is megabytes, never bytes worth reading one by one. */
  protected tailleLisible(octets: number): string {
    const mega = octets / (1024 * 1024);
    return mega >= 1 ? `${mega.toFixed(1)} Mo` : `${Math.max(1, Math.round(octets / 1024))} Ko`;
  }
}
