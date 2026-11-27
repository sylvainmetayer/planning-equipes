import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  LOCALE_ID,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { RouterLink } from '@angular/router';
import { DateMockService } from '../core/date-mock.service';
import {
  defaultPanelStorage,
  readPanelCollapsed,
  writePanelCollapsed,
} from '../core/panel-collapse';
import { situationText } from '../core/situation-wording';
import { EditionStore } from '../core/edition.store';
import { Edition } from '../core/models';
import { phaseLabel } from '../core/phase-edition';
import { SessionStore } from '../core/session.store';
import { EditionActivationActions } from './edition-activation-actions';
import { PhaseEditionActions } from './phase-edition-actions';

/** localStorage prefix of a dismissed situation (`type.edition`). */
const DISMISSED_PREFIX = 'planning.bandeau-edition-masque.';

/**
 * Persistent strip under the toolbar naming the edition every screen is
 * currently reading from — and whether it is the active one, the only one
 * that reaches outside (ADR 0072) — with a one-click switcher, and a banner
 * when the editions' state asks for a decision (the active one is over, the
 * next one starts within days), or while the structure of a published edition
 * is unblocked (ADR 0115) — the one state that must not be forgotten open.
 * Every page's data
 * depends on that answer, so it is given once in the shell instead of on any
 * one page — see `docs/decisions/0001-cloisonnement-par-edition.md`.
 *
 * Switching reloads the page rather than refreshing the stores: it swaps the
 * data behind *every* open screen at once, and a full reload is the only way
 * to guarantee no page keeps rendering the previous edition's rows (same
 * reasoning as the language toggle). It is a rare, deliberate action.
 */
@Component({
  selector: 'app-edition-actuelle-bar',
  imports: [MatButtonModule, MatIconModule, MatMenuModule, RouterLink],
  template: `
    @if (editionActuelle(); as edition) {
      <!-- Not a live region as a whole: it would announce the edition and the
           edition on every first render, when nothing changed. Only
           the switching action speaks, and it says so itself. -->
      <div class="edition-actuelle-bar">
        <mat-icon class="edition-actuelle-bar-icon">layers</mat-icon>
        <span class="edition-actuelle-bar-label">
          <span i18n="@@editionActuelle.label">Édition actuelle :</span>
          <strong>{{ edition.nom }}</strong>
          @if (phase(); as nomPhase) {
            <span class="edition-actuelle-bar-phase" i18n="@@editionActuelle.phase">· phase {{ nomPhase }}</span>
          }
          @if (!edition.active) {
            <span class="edition-actuelle-bar-inactive" i18n="@@editionActuelle.inactive">(inactive : rien n'en part vers l'extérieur)</span>
          }
        </span>
        <span class="edition-actuelle-bar-actions">
          @if (autresEditions().length > 0) {
            <button
              matButton
              [matMenuTriggerFor]="menu"
              i18n-aria-label="@@editionActuelle.switchAriaLabel"
              aria-label="Changer d'édition"
            >
              <span i18n="@@editionActuelle.switch">Changer</span>
              <mat-icon iconPositionEnd>expand_more</mat-icon>
            </button>
            <mat-menu #menu="matMenu">
              @for (autre of autresEditions(); track autre.id) {
                <button mat-menu-item (click)="basculer(autre)">{{ autre.nom }}</button>
              }
            </mat-menu>
          }
          <a
            matButton
            routerLink="/editions"
            i18n-aria-label="@@editionActuelle.manageAriaLabel"
            aria-label="Gérer les éditions"
          >
            <span i18n="@@editionActuelle.manage">Gérer</span>
          </a>
        </span>
      </div>
      @if (structureDebloquee()) {
        <div class="edition-actuelle-bar edition-actuelle-bar-rappel" role="status" data-bandeau="structure-debloquee">
          <mat-icon class="edition-actuelle-bar-icon">lock_open</mat-icon>
          <span i18n="@@editionActuelle.debloquee">Structure débloquée : stands, créneaux, typologies et emplacements se modifient. La publication suivante la refige.</span>
          @if (!lectureSeule()) {
            <span class="edition-actuelle-bar-actions">
              <button matButton type="button" [disabled]="actions.busy()" (click)="refiger()"
                      i18n="@@phase.refiger.geste">Refiger</button>
            </span>
          }
        </div>
      }
      @for (ligne of lignes(); track ligne.cle) {
        <div class="edition-actuelle-bar edition-actuelle-bar-rappel" role="status" data-bandeau="situation">
          <mat-icon class="edition-actuelle-bar-icon">notification_important</mat-icon>
          <span><strong i18n="@@editionActuelle.rappel">Action requise :</strong> {{ ligne.texte }}</span>
          <span class="edition-actuelle-bar-actions">
            @if (!lectureSeule()) {
              @if (ligne.situation.type === 'ACTIVE_TERMINEE') {
                <button matButton type="button" [disabled]="busy()" (click)="agir(ligne.situation.edition, 'desactiver')"
                        [attr.aria-label]="labelDesactiver(ligne.situation.edition)"
                        i18n="@@editionActuelle.desactiver">Désactiver</button>
                @if (suivante(); as next) {
                  <button matButton type="button" [disabled]="busy()" (click)="agir(next, 'activer')"
                          [attr.aria-label]="labelActiver(next)"
                          i18n="@@editionActuelle.activerSuivante">Activer la suivante</button>
                }
              } @else {
                <button matButton type="button" [disabled]="busy()" (click)="agir(ligne.situation.edition, 'activer')"
                        [attr.aria-label]="labelActiver(ligne.situation.edition)"
                        i18n="@@editionActuelle.activer">Activer</button>
              }
            }
            <a matButton routerLink="/editions" i18n="@@editionActuelle.rappelLien">Voir les éditions</a>
            <button matButton type="button" (click)="dismiss(ligne.cle)"
                    [attr.aria-label]="labelDismiss(ligne.situation.edition)"
                    i18n="@@editionActuelle.neplusafficher">Ne plus afficher</button>
          </span>
        </div>
      }
    }
  `,
  styles: `
    .edition-actuelle-bar {
      position: sticky;
      top: 0;
      z-index: 2;
      display: flex;
      align-items: center;
      gap: 0.5rem;
      padding: 0.25rem 1.25rem;
      background: var(--mat-sys-secondary-container);
      color: var(--mat-sys-on-secondary-container);
      border-bottom: 1px solid var(--mat-sys-outline-variant);
      font: var(--mat-sys-body-medium);
    }
    .edition-actuelle-bar-icon {
      flex-shrink: 0;
    }
    .edition-actuelle-bar-inactive,
    .edition-actuelle-bar-phase {
      font: var(--mat-sys-body-small);
      white-space: nowrap;
    }
    .edition-actuelle-bar-rappel {
      position: static;
      background: var(--mat-sys-tertiary-container);
      color: var(--mat-sys-on-tertiary-container);
    }
    .edition-actuelle-bar-label {
      display: flex;
      align-items: center;
      gap: 0.35rem;
      min-width: 0;
    }
    .edition-actuelle-bar-label strong {
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .edition-actuelle-bar-groupe {
      display: flex;
      align-items: center;
      min-width: 0;
      margin-left: 1rem;
      padding-left: 1rem;
      border-left: 1px solid var(--mat-sys-outline-variant);
    }
    .edition-actuelle-bar-actions {
      display: flex;
      align-items: center;
      gap: 0.25rem;
      margin-left: auto;
    }
    @media (max-width: 740px) {
      .edition-actuelle-bar {
        padding-inline: 0.75rem;
      }
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EditionActuelleBar {
  private readonly store = inject(EditionStore);

  protected readonly editionActuelle = computed(() => this.store.courant());
  protected readonly autresEditions = computed(() => this.store.autres());
  private readonly locale = inject(LOCALE_ID);
  private readonly clock = inject(DateMockService);
  private readonly activation = inject(EditionActivationActions);
  private readonly storage = defaultPanelStorage();
  /** Keys hidden in this session; the ones of earlier sessions are read from the browser. */
  private readonly masquees = signal<ReadonlySet<string>>(new Set());
  protected readonly busy = signal(false);
  /** One line per situation not dismissed: a dismissal is for one type on one edition. */
  protected readonly lignes = computed(() => {
    return this.store
      .situations()
      .map((situation) => ({ situation, cle: `${situation.type}.${situation.edition.id}` }))
      .filter(
        ({ cle }) =>
          !this.masquees().has(cle) && !readPanelCollapsed(this.storage, DISMISSED_PREFIX + cle),
      )
      .map((ligne) => ({
        ...ligne,
        texte: situationText(ligne.situation, this.locale, this.clock.dateDuJour()),
      }));
  });
  /** The edition to start once the active one is over, when the state names one. */
  protected readonly suivante = computed(
    () =>
      this.store
        .situations()
        .filter((s) => s.type === 'INACTIVE_IMMINENTE' || s.type === 'INACTIVE_EN_COURS')
        .sort((a, b) => (a.premierJour ?? '').localeCompare(b.premierJour ?? ''))[0]?.edition,
  );
  protected readonly phase = computed(() => {
    const phase = this.store.phase();
    return phase ? phaseLabel(phase) : '';
  });
  protected readonly structureDebloquee = this.store.structureDebloquee;
  protected readonly lectureSeule = inject(SessionStore).lectureSeule;
  protected readonly actions = inject(PhaseEditionActions);

  protected labelActiver(edition: Edition): string {
    const nom = edition.nom;
    return $localize`:@@editionActuelle.activerAria:Activer l'édition ${nom}:nom:`;
  }

  protected labelDesactiver(edition: Edition): string {
    const nom = edition.nom;
    return $localize`:@@editionActuelle.desactiverAria:Désactiver l'édition ${nom}:nom:`;
  }

  protected labelDismiss(edition: Edition): string {
    const nom = edition.nom;
    return $localize`:@@editionActuelle.neplusafficherAria:Ne plus afficher l'alerte sur l'édition ${nom}:nom:`;
  }

  protected dismiss(cle: string): void {
    writePanelCollapsed(this.storage, DISMISSED_PREFIX + cle, true);
    this.masquees.update((anciennes) => new Set(anciennes).add(cle));
  }

  protected async agir(edition: Edition, geste: 'activer' | 'desactiver'): Promise<void> {
    this.busy.set(true);
    try {
      await (geste === 'activer'
        ? this.activation.activate(edition)
        : this.activation.deactivate(edition));
    } finally {
      this.busy.set(false);
    }
  }

  protected refiger(): void {
    void this.actions.refreezeStructure();
  }

  protected basculer(edition: Edition): void {
    this.store.basculer(edition);
  }
}
