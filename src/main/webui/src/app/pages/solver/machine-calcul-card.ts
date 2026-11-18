import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MachineCalculApi } from '../../core/api/machine-calcul-api';
import { errorPrefix } from '../../core/error-message';
import { intlLocale } from '../../core/locale';
import { MachineCalcul } from '../../core/models';
import { StatusMessage } from '../../shared/status-message';

/**
 * The compute machine created on demand (ADR 0086): its state in words, when
 * it will stop, what it cost this month, and the two gestures on it. The page
 * reads the view and renders this card only for an on-demand machine.
 */
@Component({
  selector: 'app-machine-calcul-card',
  imports: [MatCardModule, MatButtonModule, MatIconModule, StatusMessage],
  template: `
    <mat-card appearance="outlined" class="page-card">
      <mat-card-header>
        <h2 mat-card-title i18n="@@solver.machine.title">Machine de calcul</h2>
      </mat-card-header>
      <mat-card-content>
        <p>{{ etat() }}</p>
        @if (arretPrevu()) {
          <p class="calendar-meta">{{ arretPrevu() }}</p>
        }
        @if (machine().billedHoursThisMonth !== null) {
          <p class="calendar-meta" i18n="@@solver.machine.heures">{{ machine().billedHoursThisMonth }} h de machine ce mois-ci</p>
        }
        <app-status-message [text]="erreurArret()" tone="error" />
      </mat-card-content>
      @if (canPrepare() || canStop()) {
        <mat-card-actions class="card-actions">
          @if (canPrepare()) {
            <button matButton="tonal" type="button" [disabled]="busy()" (click)="act('prepare')">
              <mat-icon>power_settings_new</mat-icon>
              <ng-container i18n="@@solver.machine.preparer">Préparer la machine</ng-container>
            </button>
          }
          @if (canStop()) {
            <button matButton type="button" [disabled]="busy()" (click)="act('stop')">
              <mat-icon>power_off</mat-icon>
              <ng-container i18n="@@solver.machine.arreter">Arrêter la machine maintenant</ng-container>
            </button>
          }
        </mat-card-actions>
      }
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class MachineCalculCard {
  private readonly api = inject(MachineCalculApi);

  readonly machine = input.required<MachineCalcul>();
  /** The view the server answered after a gesture. */
  readonly changed = output<MachineCalcul>();
  /** Why a gesture was refused, for the page's output panel. */
  readonly failed = output<string>();

  protected readonly busy = signal(false);

  protected readonly etat = computed(() => {
    switch (this.machine().state) {
      case 'PREPARING':
        return $localize`:@@solver.machine.etat.preparation:En préparation.`;
      case 'READY':
        return $localize`:@@solver.machine.etat.prete:Prête.`;
      case 'SOLVING':
        return $localize`:@@solver.machine.etat.calcul:En calcul.`;
      default:
        return $localize`:@@solver.machine.etat.absente:Absente : elle sera créée au prochain calcul déporté.`;
    }
  });

  protected readonly arretPrevu = computed(() => {
    const stopsAt = this.machine().stopsAt;
    if (!stopsAt) {
      return '';
    }
    const heure = new Date(stopsAt).toLocaleTimeString(intlLocale(), {
      hour: '2-digit',
      minute: '2-digit',
    });
    return $localize`:@@solver.machine.arretPrevu:Arrêt prévu à ${heure}:heure:.`;
  });

  protected readonly erreurArret = computed(() => {
    const erreur = this.machine().lastStopError;
    return erreur
      ? $localize`:@@solver.machine.erreurArret:La machine n'a pas pu être arrêtée et reste facturée : ${erreur}:erreur:`
      : '';
  });

  protected readonly canPrepare = computed(() => this.machine().state === 'ABSENT');
  protected readonly canStop = computed(() => {
    const state = this.machine().state;
    return state === 'READY' || state === 'PREPARING';
  });

  protected async act(gesture: 'prepare' | 'stop'): Promise<void> {
    this.busy.set(true);
    try {
      this.changed.emit(await (gesture === 'prepare' ? this.api.prepare() : this.api.stop()));
    } catch (error) {
      this.failed.emit(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }
}
