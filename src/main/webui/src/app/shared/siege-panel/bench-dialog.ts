// « Qui peut tenir ce siège ? » — the bench of one seat, opened from the Siège
// panel. It used to be a Diagnostic tab choosing its own seat, read-only; it is
// now asked about the very seat the reader pointed at. On an empty seat each
// line the write would take carries « Placer »; on a held one the bench only
// answers « qui pourrait le remplacer ? ». The dialog chooses; the panel writes.

import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
} from '@angular/core';
import { NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AnalysesApi } from '../../core/api/analyses-api';
import { Animateur, ConstraintView, MotifExclusion } from '../../core/models';
import { errorText } from '../../core/resource-state';
import { SolverJobService } from '../../core/solver-job.service';
import { VerrouillageStore } from '../../core/verrouillage.store';
import { nomAnimateur } from '../affectation-explanation-rules';
import { StatusMessage } from '../status-message';
import {
  BenchLine,
  BenchState,
  benchLines,
  PlacementContext,
  placeable,
  placementBlock,
  splitLines,
} from './bench';
import { levelLabel, ruleLabel } from './seat';

export interface BenchDialogData {
  posteId: string;
  creneauId: number;
  standId: string | null;
  /** « Stand 51 · Jour 5 · 18:00–22:00 »: the seat asked about, as the panel names it. */
  title: string;
  /** The plan's people, to name the lines — the answer carries ids only. */
  animateurs: Animateur[];
  /** The rule catalogue, for the reasons' short labels; null when it could not be read. */
  catalogue: ReadonlyMap<string, ConstraintView> | null;
  /**
   * False on a held seat: the bench is read, nobody is placed — the seat is
   * somebody's, and « Remplacer » is the gesture that hands it over.
   */
  offerPlacement: boolean;
}

/** What the dialog closes with when somebody was chosen; `undefined` when it was only consulted. */
export interface BenchChoice {
  animateurId: string;
  /** « La garder au prochain calcul » : the panel then locks the placement. */
  keep: boolean;
}

@Component({
  selector: 'app-bench-dialog',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCheckboxModule,
    MatDialogModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    NgTemplateOutlet,
    StatusMessage,
  ],
  template: `
    <h2 mat-dialog-title i18n="@@banc.dialog.title">Qui peut tenir ce siège ?</h2>
    <mat-dialog-content>
      <p class="bench-seat">
        <mat-icon inline aria-hidden="true">event_seat</mat-icon>
        {{ data.title }}
      </p>
      @if (bench.isLoading()) {
        <mat-progress-bar mode="indeterminate" />
      }
      <app-status-message [text]="error()" tone="error" />

      @if (bench.hasValue()) {
        @if (holderName(); as holder) {
          <p class="bench-muted" i18n="@@banc.dialog.tenu">
            Ce siège est tenu par {{ holder }} : la liste dit qui pourrait le remplacer.
          </p>
        } @else if (bench.value().seatStarted) {
          <p class="bench-muted">
            <mat-icon inline aria-hidden="true">history</mat-icon>
            <ng-container i18n="@@banc.dialog.commence"
              >Ce créneau est déjà commencé : personne n'y est plus placé à la main.</ng-container
            >
          </p>
        }
        @if (lines().length === 0) {
          <p class="bench-muted">
            <mat-icon inline aria-hidden="true">info_outline</mat-icon>
            {{ emptyExplanation() }}
          </p>
        } @else {
          <p class="bench-summary">{{ summary() }}</p>
          @if (placing()) {
            <mat-checkbox [ngModel]="keep()" (ngModelChange)="keep.set($event)" i18n="@@banc.dialog.garder"
              >La garder au prochain calcul</mat-checkbox
            >
          }
          <ul class="bench-list">
            @for (line of shown(); track line.animateurId) {
              <ng-container [ngTemplateOutlet]="row" [ngTemplateOutletContext]="{ $implicit: line }" />
            }
          </ul>
          @if (folded().length > 0) {
            @if (showAll()) {
              <ul class="bench-list">
                @for (line of folded(); track line.animateurId) {
                  <ng-container [ngTemplateOutlet]="row" [ngTemplateOutletContext]="{ $implicit: line }" />
                }
              </ul>
            } @else {
              <button matButton type="button" (click)="showAll.set(true)">
                {{ foldedLabel() }}
              </button>
            }
          }
        }
      }

      <ng-template #row let-line>
        <li [class]="'bench-line bench-line-' + line.state">
          <div class="bench-line-head">
            <strong>{{ line.nom }}</strong>
            <span class="bench-state">
              <mat-icon inline aria-hidden="true">{{ stateIcon(line.state) }}</mat-icon>
              {{ stateLabel(line.state) }}
            </span>
            @if (isPlaceable(line)) {
              <button
                matButton="filled"
                type="button"
                class="bench-place"
                [disabled]="editingLocked()"
                [attr.aria-label]="placeLabel(line)"
                (click)="place(line)"
                i18n="@@banc.dialog.placer"
              >
                Placer
              </button>
            } @else if (isLocked(line)) {
              <span class="bench-blocked bench-muted">
                <mat-icon inline aria-hidden="true">lock</mat-icon>
                <ng-container i18n="@@banc.dialog.verrouille"
                  >Verrouillé(e) : pas de nouveau siège sur ce créneau</ng-container
                >
              </span>
            }
          </div>
          @if (line.motifs.length === 0) {
            <span class="bench-muted" i18n="@@bancDeTouche.noReason"
              >Aucune règle ne s'y oppose</span
            >
          } @else {
            <span class="bench-reasons">
              @for (motif of line.motifs; track motif.contrainte) {
                <span
                  class="bench-reason"
                  [class.bench-reason-hard]="motif.niveau === 'HARD'"
                  [matTooltip]="reasonTooltip(motif)"
                  >{{ reasonLabel(motif) }}</span
                >
                <span class="visually-hidden"> — {{ reasonTooltip(motif) }}</span>
              }
            </span>
          }
        </li>
      </ng-template>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton type="button" mat-dialog-close i18n="@@violationDetails.close">
        Fermer
      </button>
    </mat-dialog-actions>
  `,
  styles: `
    .bench-seat,
    .bench-summary {
      margin: 0.25rem 0;
    }
    .bench-summary {
      font: var(--mat-sys-title-small);
    }
    .bench-muted {
      color: var(--mat-sys-on-surface-variant);
    }
    .bench-list {
      list-style: none;
      margin: 0.5rem 0;
      padding: 0;
    }
    .bench-line {
      display: flex;
      flex-direction: column;
      gap: 0.25rem;
      padding: 0.5rem 0;
      border-bottom: 1px solid var(--mat-sys-outline-variant);
    }
    .bench-line-head {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.5rem;
    }
    .bench-place,
    .bench-blocked {
      margin-left: auto;
    }
    .bench-state {
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
      font: var(--mat-sys-body-small);
    }
    .bench-line-disponible .bench-state {
      color: var(--mat-sys-primary);
    }
    .bench-line-sousReserve .bench-state {
      color: var(--mat-sys-tertiary);
    }
    .bench-line-impossible .bench-state {
      color: var(--mat-sys-error);
    }
    .bench-reason {
      display: inline-block;
      margin: 0.1rem 0.25rem 0.1rem 0;
      padding: 0.1rem 0.5rem;
      border-radius: 1rem;
      font: var(--mat-sys-label-small);
      background: var(--mat-sys-surface-container-high);
      color: var(--mat-sys-on-surface-variant);
      cursor: help;
    }
    .bench-reason-hard {
      background: var(--mat-sys-error-container);
      color: var(--mat-sys-on-error-container);
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class BenchDialog {
  protected readonly data = inject<BenchDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject<MatDialogRef<BenchDialog, BenchChoice>>(MatDialogRef);
  private readonly analysesApi = inject(AnalysesApi);
  private readonly verrous = inject(VerrouillageStore);
  /** A solve holding the edition refuses the write: « Placer » waits for it. */
  protected readonly editingLocked = inject(SolverJobService).editingLocked;

  /** The bench of this very seat — the server evaluates every hypothesis on it. */
  protected readonly bench = resource({
    loader: () => this.analysesApi.bench(this.data.creneauId, this.data.standId, this.data.posteId),
  });
  protected readonly error = errorText(this.bench);

  /** Checked by default: a placement made by hand is one the next solve should keep. */
  protected readonly keep = signal(true);
  protected readonly showAll = signal(false);

  protected readonly lines = computed<BenchLine[]>(() =>
    benchLines(this.bench.hasValue() ? this.bench.value() : null, this.data.animateurs),
  );
  private readonly split = computed(() => splitLines(this.lines()));
  protected readonly shown = computed(() => this.split().shown);
  protected readonly folded = computed(() => this.split().folded);

  protected readonly holderName = computed(() => {
    const holder = this.bench.hasValue() ? this.bench.value().animateurCibleId : null;
    return holder ? nomAnimateur({ animateurs: this.data.animateurs }, holder) : '';
  });

  /**
   * What the placement write reads besides the rules. A seat the server says
   * is held or started places nobody, whatever the panel believed: its plan
   * may be older than the bench's.
   */
  private readonly context = computed<PlacementContext>(() => ({
    creneauId: this.data.creneauId,
    seatStarted: this.bench.hasValue() && this.bench.value().seatStarted,
    locks: this.verrous.verrouillages(),
  }));
  /** Somebody can be placed from here at all: an empty seat, a timeslot still ahead. */
  protected readonly placing = computed(
    () =>
      this.data.offerPlacement &&
      this.bench.hasValue() &&
      !this.bench.value().animateurCibleId &&
      !this.bench.value().seatStarted,
  );

  protected readonly summary = computed(() => {
    if (!this.bench.hasValue()) {
      return '';
    }
    const { disponibles, total } = this.bench.value();
    return $localize`:@@bancDeTouche.summary:${disponibles}:available: animateurs disponibles sur ${total}:total: hors service à ce créneau`;
  });

  protected readonly foldedLabel = computed(() => {
    const count = this.folded().length;
    return $localize`:@@banc.dialog.autres:Voir les ${count}:count: autres, qu'une règle dure écarte`;
  });

  /**
   * Having nothing to say about a seat is one of the answers, not a failure:
   * no saved plan, or nobody off duty then. A seat the plan no longer holds
   * is not one of them: the dialog always names its seat, and the server
   * answers a vanished one with an error the dialog words.
   */
  protected emptyExplanation(): string {
    const statut = this.bench.hasValue() ? this.bench.value().statut : null;
    if (statut === 'NO_PLAN') {
      return $localize`:@@bancDeTouche.noPlan:Aucun planning enregistré : lancez une résolution pour que cet écran ait un plan à interroger.`;
    }
    return $localize`:@@bancDeTouche.everyoneOnDuty:Tout le monde est de service sur ce créneau : le banc est vide.`;
  }

  protected isPlaceable(line: BenchLine): boolean {
    return this.placing() && placeable(line, this.context());
  }

  /** Available by the rules, refused by a lock: said on the line rather than a « Placer » the write turns down. */
  protected isLocked(line: BenchLine): boolean {
    return (
      this.placing() &&
      line.state === 'disponible' &&
      placementBlock(line, this.context()) === 'locked'
    );
  }

  protected placeLabel(line: BenchLine): string {
    const nom = line.nom;
    return $localize`:@@banc.dialog.placer.label:Placer ${nom}:nom: sur ce siège`;
  }

  protected place(line: BenchLine): void {
    this.ref.close({ animateurId: line.animateurId, keep: this.keep() });
  }

  /** The chip: the rule's short label from the catalogue, never its Java name. */
  protected reasonLabel(motif: MotifExclusion): string {
    return ruleLabel(motif.contrainte, this.data.catalogue, null);
  }

  /** The tooltip: the level in words, then the catalogue's own wording of the rule. */
  protected reasonTooltip(motif: MotifExclusion): string {
    return [levelLabel(motif.niveau), motif.categorie, motif.description]
      .filter(Boolean)
      .join(' — ');
  }

  protected stateLabel(state: BenchState): string {
    switch (state) {
      case 'disponible':
        return $localize`:@@bancDeTouche.state.available:Disponible`;
      case 'sousReserve':
        return $localize`:@@bancDeTouche.state.underReserve:Possible, mais casse une règle`;
      default:
        return $localize`:@@bancDeTouche.state.impossible:Impossible`;
    }
  }

  protected stateIcon(state: BenchState): string {
    switch (state) {
      case 'disponible':
        return 'check_circle';
      case 'sousReserve':
        return 'warning';
      default:
        return 'block';
    }
  }
}

/** Opens the dialog; it closes with the chosen person, or `undefined` when dismissed. */
export function openBenchDialog(dialog: MatDialog, data: BenchDialogData) {
  return dialog.open<BenchDialog, BenchDialogData, BenchChoice>(BenchDialog, {
    data,
    width: '36rem',
    maxWidth: '95vw',
    autoFocus: 'first-tabbable',
    restoreFocus: true,
  });
}
