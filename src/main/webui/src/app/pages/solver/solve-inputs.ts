import {
  ChangeDetectionStrategy,
  Component,
  computed,
  effect,
  inject,
  input,
  signal,
  untracked,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { PlanningApi } from '../../core/api/planning-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { SolveInputs } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';

/** One counter of the summary: its sentence, and the screen that lists what it counts. */
export interface SolveInputLine {
  key: string;
  count: number;
  text: string;
  route: string;
  queryParams: Record<string, string>;
  /** Something the solve will not see, rather than something it will respect. */
  warning: boolean;
}

/** `2026-08-02` → « 02/08 »: the consignes are named by their day, the year is the edition's. */
function shortDate(date: string): string {
  const [annee, mois, jour] = date.split('-').map(Number);
  return new Date(annee, mois - 1, jour).toLocaleDateString(intlLocale(), {
    day: '2-digit',
    month: '2-digit',
  });
}

/**
 * The lines of « Ce calcul tiendra compte de », in the order a reader checks
 * them: what the solve must respect (locks, adjustments, consignes), what it
 * will not see (declarations still waiting), what it was told to ignore (rules
 * switched off), and how far the plan is behind the data. Pure, so the
 * wording and the links are tested without rendering.
 */
export function solveInputLines(inputs: SolveInputs): SolveInputLine[] {
  const dates = inputs.consignes.map((consigne) => shortDate(consigne.date)).join(', ');
  return [
    {
      key: 'locks',
      count: inputs.locks,
      text: $localize`:@@solver.entrees.verrous:${inputs.locks}:count: verrouillage(s)`,
      route: '/consignes-solveur',
      queryParams: { onglet: 'verrouillages' },
      warning: false,
    },
    {
      key: 'adjustments',
      count: inputs.adjustments,
      text: $localize`:@@solver.entrees.ajustements:${inputs.adjustments}:count: ajustement(s) manuel(s)`,
      route: '/consignes-solveur',
      queryParams: { onglet: 'ajustements' },
      warning: false,
    },
    {
      key: 'consignes',
      count: inputs.consignes.length,
      text:
        inputs.consignes.length === 0
          ? $localize`:@@solver.entrees.consignes.aucune:0 consigne`
          : $localize`:@@solver.entrees.consignes:${inputs.consignes.length}:count: consigne(s) (${dates}:dates:)`,
      route: '/consignes-solveur',
      queryParams: { onglet: 'consignes' },
      warning: false,
    },
    {
      key: 'declarations',
      count: inputs.pendingDeclarations,
      text: $localize`:@@solver.entrees.declarations:${inputs.pendingDeclarations}:count: déclaration(s) de disponibilité non traitée(s), que le calcul ne verra pas`,
      route: '/disponibilites',
      queryParams: { statut: 'en-attente' },
      warning: true,
    },
    {
      key: 'rules',
      count: inputs.disabledRules,
      text: $localize`:@@solver.entrees.regles:${inputs.disabledRules}:count: règle(s) désactivée(s)`,
      route: '/regles',
      queryParams: {},
      warning: false,
    },
    {
      key: 'changes',
      count: inputs.changesSinceSolve,
      text: $localize`:@@solver.entrees.modifications:${inputs.changesSinceSolve}:count: modification(s) depuis la dernière résolution`,
      route: '/historique',
      queryParams: {},
      warning: false,
    },
  ];
}

/**
 * « Ce calcul tiendra compte de » (issues #704, #719): what the next solve
 * receives, counted before the click, each counter a link to the screen that
 * lists what it counts. A counter at zero stays, muted and without a link: its
 * absence would read as « not checked ».
 *
 * <p>Read on arrival and again each time `version` moves — the page bumps it
 * when a solve lands; a lock, an adjustment or a consigne written meanwhile is
 * on another page, which the reader leaves to come back here.</p>
 */
@Component({
  selector: 'app-solve-inputs',
  imports: [MatButtonModule, MatCardModule, MatIconModule, RouterLink, StatusMessage],
  template: `
    <mat-card appearance="outlined" class="page-card solve-inputs">
      <mat-card-header>
        <h2 mat-card-title i18n="@@solver.entrees.title">Ce calcul tiendra compte de</h2>
      </mat-card-header>
      <mat-card-content>
        <app-status-message [text]="error()" tone="error" />
        @if (lines(); as items) {
          <ul class="solve-inputs-list">
            @for (line of items; track line.key) {
              <li [class.solve-inputs-zero]="line.count === 0" [class.solve-inputs-warning]="line.warning && line.count > 0">
                @if (line.count > 0) {
                  @if (line.warning) {
                    <mat-icon inline aria-hidden="true">warning</mat-icon>
                  }
                  <a [routerLink]="line.route" [queryParams]="line.queryParams">{{ line.text }}</a>
                } @else {
                  {{ line.text }}
                }
              </li>
            }
          </ul>
        }
      </mat-card-content>
      <mat-card-actions class="card-actions">
        <!-- A consigne cannot touch a day already begun: tomorrow is the
             nearest date it can close. -->
        <a matButton routerLink="/consignes-solveur" [queryParams]="{ onglet: 'consignes', date: 'demain', nouvelle: '1' }">
          <mat-icon>gavel</mat-icon>
          <ng-container i18n="@@consignes.fermerDemain">Fermer des stands demain</ng-container>
        </a>
      </mat-card-actions>
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class SolveInputsCard {
  /** Moved by the page when a solve lands: the plan, and so the counts, changed. */
  readonly version = input(0);

  private readonly api = inject(PlanningApi);

  private readonly inputs = signal<SolveInputs | null>(null);
  protected readonly error = signal('');

  protected readonly lines = computed(() => {
    const inputs = this.inputs();
    return inputs ? solveInputLines(inputs) : null;
  });

  constructor() {
    effect(() => {
      this.version();
      untracked(() => void this.reload());
    });
  }

  async reload(): Promise<void> {
    try {
      this.inputs.set(await this.api.solveInputs());
      this.error.set('');
    } catch (error) {
      this.error.set(errorPrefix(error));
    }
  }
}
