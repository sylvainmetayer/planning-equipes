import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { ApiService } from '../../core/api.service';
import { errorMessage } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';

/** What the nightly jobs are allowed to do on this edition (issues #298, #299, #300). */
export interface ParametresNotifications {
  actives: boolean;
  /** `HH:mm` local time, from which the day-before reminder may go out. */
  heureRappelVeille: string;
  delaiRelanceHeures: number;
  ancienneteEchangeJours: number;
}

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
 * year's volunteers are not the ones to remind about tomorrow.
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
    MatSlideToggleModule
  ],
  templateUrl: './parametres-notifications.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class ParametresNotificationsPanel {
  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);

  protected readonly parametres = signal<ParametresNotifications | null>(null);
  protected readonly enregistrement = signal(false);

  constructor() {
    void this.charger();
  }

  private async charger(): Promise<void> {
    try {
      this.parametres.set(await this.api.get<ParametresNotifications>('/api/parametres-notifications'));
    } catch {
      // The rest of the Paramètres page must stay usable; the panel simply
      // does not render until a reload succeeds.
      this.parametres.set(null);
    }
  }

  protected majActives(actives: boolean): void {
    this.patch({ actives });
  }

  protected majHeure(heureRappelVeille: string): void {
    this.patch({ heureRappelVeille });
  }

  protected majDelaiRelance(valeur: string): void {
    this.patch({ delaiRelanceHeures: Number(valeur) });
  }

  protected majAnciennete(valeur: string): void {
    this.patch({ ancienneteEchangeJours: Number(valeur) });
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
    try {
      this.parametres.set(
        await this.api.put<ParametresNotifications>('/api/parametres-notifications', parametres)
      );
      this.notifications.notify({
        title: $localize`:@@parametres.notifications.enregistre:Notifications planifiées enregistrées.`,
        variant: 'success',
        timeout: 4000
      });
    } catch (error) {
      this.notifications.notify({
        title: $localize`:@@crud.error:Erreur`,
        message: errorMessage(error),
        variant: 'error'
      });
    } finally {
      this.enregistrement.set(false);
    }
  }
}
