import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { AdminApi } from '../../core/api/admin-api';
import { ApiError } from '../../core/api.service';
import { ParametresNotifications } from '../../core/models';
import { errorMessage } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { StatusMessage } from '../../shared/status-message';

/**
 * Latest sending time the hourly job can honour — mirrors
 * `ParametresNotifications.HEURE_RAPPEL_VEILLE_MAX` on the server, which
 * refuses anything later rather than accepting a setting that would never fire.
 */
const HEURE_RAPPEL_MAX = '23:00';

/** What the nightly jobs are allowed to do on this edition (issues #298, #299, #300). */

/**
 * Per-edition settings of the scheduled notifications.
 *
 * A component of its own rather than another block inside `parametres-page`:
 * the page is already long, these four fields form one decision ("may this
 * edition write to people, and how patiently"), and keeping them here means the
 * page carries a single tag.
 *
 * The toggle is the guard rail of the whole feature, so the wording says what
 * it protects rather than what it does: an `Edition` has no dates and no
 * "ongoing" flag, so nothing but this switch tells a nightly job that last
 * year's animateurs are not the ones to remind about tomorrow.
 */
@Component({
  selector: 'app-parametres-notifications',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSlideToggleModule,
    StatusMessage,
  ],
  templateUrl: './parametres-notifications.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresNotificationsPanel implements OnInit {
  private readonly adminApi = inject(AdminApi);
  private readonly notifications = inject(NotificationService);

  protected readonly parametres = signal<ParametresNotifications | null>(null);
  protected readonly enregistrement = signal(false);
  /**
   * The server's refusal to arm this edition while another one is armed (a
   * 409 naming it). Kept on the card rather than in a passing snack-bar: the
   * way out is on another edition, and the sentence says which.
   */
  protected readonly refus = signal<string | null>(null);

  ngOnInit(): void {
    void this.charger();
  }

  private async charger(): Promise<void> {
    try {
      this.parametres.set(await this.adminApi.notificationSettings());
    } catch {
      // The rest of the Paramètres page must stay usable; the panel simply
      // does not render until a reload succeeds.
      this.parametres.set(null);
    }
  }

  protected majActives(actives: boolean): void {
    this.patch({ actives });
  }

  /**
   * Clearing a field is an edit in progress, not a value.
   *
   * `Number('')` is `0` and an emptied time input is `''`, so writing them
   * through would send `delaiRelanceHeures: 0` — refused by the server with a
   * raw 400 about a field the user merely blanked. The previous value is kept
   * instead, and the save button reports nothing because nothing was asked.
   */
  protected majHeure(valeur: string): void {
    if (!valeur) {
      return;
    }
    this.patch({ heureRappelVeille: valeur > HEURE_RAPPEL_MAX ? HEURE_RAPPEL_MAX : valeur });
  }

  protected majDelaiRelance(valeur: string): void {
    const heures = this.borne(valeur, 1, 720);
    if (heures !== null) {
      this.patch({ delaiRelanceHeures: heures });
    }
  }

  protected majAnciennete(valeur: string): void {
    const jours = this.borne(valeur, 1, 60);
    if (jours !== null) {
      this.patch({ ancienneteEchangeJours: jours });
    }
  }

  /**
   * The `min`/`max` of an input are a hint the browser gives, not a rule it
   * enforces on a typed value — clamping here is what keeps the request within
   * what the server accepts.
   */
  private borne(valeur: string, min: number, max: number): number | null {
    if (!valeur.trim()) {
      return null;
    }
    const saisi = Number(valeur);
    if (!Number.isFinite(saisi)) {
      return null;
    }
    return Math.min(Math.max(Math.round(saisi), min), max);
  }

  private patch(champs: Partial<ParametresNotifications>): void {
    const courant = this.parametres();
    if (courant) {
      this.parametres.set({ ...courant, ...champs });
    }
  }

  protected async enregistrer(): Promise<void> {
    const parametres = this.parametres();
    if (!parametres) {
      return;
    }
    this.enregistrement.set(true);
    this.refus.set(null);
    try {
      this.parametres.set(await this.adminApi.saveNotificationSettings(parametres));
      this.notifications.notify({
        title: $localize`:@@parametres.notifications.enregistre:Notifications planifiées enregistrées.`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        this.refus.set(error.message);
        return;
      }
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error',
      });
    } finally {
      this.enregistrement.set(false);
    }
  }
}
