import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  inject,
  OnInit,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe } from '@angular/common';
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
import { AdminApi } from '../../core/api/admin-api';
import { EditionsApi } from '../../core/api/editions-api';
import { EditionStore } from '../../core/edition.store';
import { ProblemesStore } from '../../core/problemes.store';
import { BRANDING, slugMarque } from '../../core/branding';
import { EtatSauvegarde } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { SolverJobService } from '../../core/solver-job.service';
import { ScenarioImportService } from '../../core/scenario-import.service';
import { ConfirmationRecopie } from '../../shared/confirmation-recopie';
import { FeasibilityBanner } from '../../shared/feasibility-banner';
import { InstantaneAvantAction } from '../../shared/instantane-avant-action';
import { OutputPanel } from '../../shared/output-panel';
import { AffichageMuralLinks } from './affichage-mural-links';
import { ContactOrganisationCard } from './contact-organisation-card';
import { GuichetsCard } from './guichets-card';
import { ParametresNotificationsPanel } from './parametres-notifications';
import { errorPrefix } from '../../core/error-message';
import { keepViewInQueryParams } from '../../core/view-query-params';
import { OngletParametres, readOngletParametres } from './parametres';
import { SingleKeyShortcutsToggle } from '../../shared/single-key-shortcuts-toggle';
import { GelReferentielCard } from '../../shared/gel-referentiel-card';
import { HorlogeSimuleeCard } from './horloge-simulee-card';
import { TODAY_ANCHOR } from '../../core/date-mock.service';

/**
 * Typed back before a SQL dump is replayed. Left untranslated on purpose: a
 * keyword whose spelling follows the interface language is a keyword an
 * administrator gets wrong after a language switch.
 */
export const REPLACE_KEYWORD = 'REMPLACER';

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
 *   thresholds, weights, the solve budget, the ninja typologie — is « Règles
 *   du planning ».
 * - « Affichage mural » — the links that open the control room's television
 *   without an admin session (ADR 0053): per edition, created and revoked here.
 * - « Instance » — what the operator configured and what holds for the whole
 *   database, every edition included: the nightly backup, the SQL dump
 *   import/export, the single-key shortcuts of this browser, and the simulated
 *   clock where the server allows one (a demonstration or staging server).
 *
 * The feasibility banner stays above the tabs: it speaks of the edition, not
 * of a tab. So does the output panel — an error answered on one tab survives
 * a move to another.
 */
@Component({
  selector: 'app-parametres-page',
  imports: [
    AffichageMuralLinks,
    ContactOrganisationCard,
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
    GelReferentielCard,
    HorlogeSimuleeCard,
  ],
  templateUrl: './parametres-page.html',
  styleUrl: './parametres.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresPage implements OnInit {
  /** Dump named after the deployment, so two instances' exports never collide in a downloads folder. */
  private readonly nomFichierDump = `${slugMarque(inject(BRANDING).productName)}.sql`;

  protected readonly output = signal('');
  protected readonly transferBusy = signal(false);

  /** The server-side solver lock: also covers a solve/analysis from another browser. */
  protected readonly solverBusy = computed(() => this.jobs.solverBusy());
  /** SQL dump replay rewrites the WHOLE database, every edition included: locked by any running job. */
  protected readonly transferLocked = computed(() => this.transferBusy() || this.solverBusy());

  private readonly sqlInput = viewChild.required<ElementRef<HTMLInputElement>>('sqlInput');
  /** Pre-solve diagnostic shown by the banner at the top of the page. */
  protected readonly problemes = inject(ProblemesStore);

  private readonly adminApi = inject(AdminApi);
  // Replaying a dump replaces the same data a scenario import does: the stores
  // to reload afterwards are the import's own set, not a second list.
  private readonly scenarioImport = inject(ScenarioImportService);
  private readonly recopie = inject(ConfirmationRecopie);
  private readonly instantane = inject(InstantaneAvantAction);

  private readonly jobs = inject(SolverJobService);
  private readonly notifications = inject(NotificationService);
  private readonly editions = inject(EditionStore);
  private readonly editionsApi = inject(EditionsApi);

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
    keepViewInQueryParams(() => ({
      onglet: this.onglet() === 'edition' ? null : this.onglet(),
    }));
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
    void this.chargerSauvegarde();
  }

  protected changerOnglet(onglet: OngletParametres): void {
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

  /* --------------------------------- SQL dump -------------------------------- */

  protected async onExportSql(): Promise<void> {
    this.transferBusy.set(true);
    this.output.set($localize`:@@dataTransfer.buildingSqlDump:Construction du dump SQL...`);
    try {
      this.output.set(await this.adminApi.exportDatabase(this.nomFichierDump));
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.transferBusy.set(false);
    }
  }

  protected pickSqlFile(): void {
    this.sqlInput().nativeElement.click();
  }

  protected async onSqlFileSelected(event: Event): Promise<void> {
    const file = takeFile(event);
    if (!file) {
      return;
    }
    // Not the edition name here: replaying a dump rewrites the whole database,
    // every edition included, so asking for the current edition's name would
    // describe an operation narrower than the one about to run. A keyword says
    // the truth of the scope instead.
    const confirmed = await this.recopie.demander({
      title: $localize`:@@dataTransfer.replaySqlTitle:Rejouer ce dump SQL ?`,
      message: $localize`:@@dataTransfer.replaySqlPromptMessage:${file.name}:fileName: remplace la base entière : toutes les éditions sont écrasées. L'opération est irréversible.`,
      valeurAttendue: REPLACE_KEYWORD,
      confirmLabel: $localize`:@@dataTransfer.replaySqlAction:Remplacer la base`,
    });
    if (!confirmed) {
      return;
    }
    await this.instantane.proposer($localize`:@@dataSetup.action.importSql:rejouer un dump SQL`);
    this.transferBusy.set(true);
    this.output.set(
      $localize`:@@dataTransfer.importing:Import de ${file.name}:fileName: en cours...`,
    );
    try {
      const summary = await this.adminApi.importDatabase(await file.text());
      await this.scenarioImport.rechargerApresImport();
      this.output.set(summary.message);
    } catch (error) {
      this.output.set(errorPrefix(error));
    } finally {
      this.transferBusy.set(false);
    }
  }
}

// Reads the picked file and clears the input so the same file can be picked twice.
function takeFile(event: Event): File | null {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0] ?? null;
  input.value = '';
  return file;
}
