import { ChangeDetectionStrategy, Component, inject, OnInit, signal } from '@angular/core';
import { MatCardModule } from '@angular/material/card';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { AdminApi } from '../../core/api/admin-api';
import { errorPrefix } from '../../core/error-message';
import { NotificationService } from '../../core/notification.service';
import { StatusMessage } from '../../shared/status-message';

/**
 * What the responsables de stand of this edition read on their stands (issue
 * #295): head counts, or first and last names. Off by default — while the
 * plan still moves, « deux animateurs » is a promise the organiser keeps,
 * « Bob et Alice » is not. A right granted on the Comptes screen can override
 * it either way. Never an address nor a phone number, whichever way it is set.
 */
@Component({
  selector: 'app-responsables-card',
  imports: [MatCardModule, MatSlideToggleModule, StatusMessage],
  template: `
    <mat-card appearance="outlined" class="page-card" id="responsables">
      <mat-card-header>
        <h2 mat-card-title i18n="@@parametres.responsables.title">Responsables de stand</h2>
        <mat-card-subtitle i18n="@@parametres.responsables.subtitle"
          >Ce qu'ils lisent du planning publié de leurs stands. Tant que le planning bouge, préférez
          les effectifs : « deux animateurs » se tient, « Bob et Alice » peut changer.</mat-card-subtitle
        >
      </mat-card-header>
      <mat-card-content>
        <mat-slide-toggle
          [checked]="nominatif()"
          [disabled]="busy() || !loaded()"
          (change)="save($event.checked)"
          i18n="@@parametres.responsables.toggle"
          >Afficher les prénoms et noms des animateurs</mat-slide-toggle
        >
        <p class="empty-hint" i18n="@@parametres.responsables.hint">
          Éteint : effectifs seuls (places pourvues et vides), sans nom. Jamais d'adresse ni de
          téléphone. Un droit accordé dans Comptes peut faire exception.
        </p>
        <app-status-message [text]="error()" tone="error" />
      </mat-card-content>
    </mat-card>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ResponsablesCard implements OnInit {
  private readonly adminApi = inject(AdminApi);
  private readonly notifications = inject(NotificationService);

  protected readonly busy = signal(false);
  protected readonly loaded = signal(false);
  protected readonly error = signal('');
  protected readonly nominatif = signal(false);

  ngOnInit(): void {
    void this.load();
  }

  private async load(): Promise<void> {
    try {
      this.nominatif.set((await this.adminApi.responsablesSettings()).nominatif);
      this.loaded.set(true);
    } catch (error) {
      this.error.set(errorPrefix(error));
    }
  }

  protected async save(nominatif: boolean): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      this.nominatif.set((await this.adminApi.saveResponsablesSettings({ nominatif })).nominatif);
      this.notifications.notify({
        title: nominatif
          ? $localize`:@@parametres.responsables.savedNoms:Les responsables de stand lisent désormais les noms`
          : $localize`:@@parametres.responsables.savedEffectifs:Les responsables de stand ne lisent plus que les effectifs`,
        variant: 'success',
        timeout: 4000,
      });
    } catch (error) {
      this.nominatif.set(!nominatif);
      this.error.set(errorPrefix(error));
    } finally {
      this.busy.set(false);
    }
  }
}
