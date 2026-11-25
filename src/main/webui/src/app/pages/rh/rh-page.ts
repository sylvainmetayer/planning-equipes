import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  resource,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatSelectModule } from '@angular/material/select';
import { MatToolbarModule } from '@angular/material/toolbar';
import { AdminApi } from '../../core/api/admin-api';
import { RhApi } from '../../core/api/rh-api';
import { errorText } from '../../core/resource-state';
import { BrandLogo } from '../../shared/brand-logo';
import { StatusMessage } from '../../shared/status-message';
import { ConformiteRapport } from './conformite-rapport';

/**
 * « Conformité » (`/rh`): the one screen of an account whose only right is an RH
 * one, outside the administration — the report of the edition it picks among
 * those its rights name.
 */
@Component({
  selector: 'app-rh-page',
  imports: [
    BrandLogo,
    ConformiteRapport,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatSelectModule,
    MatToolbarModule,
    StatusMessage,
  ],
  templateUrl: './rh-page.html',
  styleUrl: './rh-page.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class RhPage {
  private readonly api = inject(RhApi);
  private readonly adminApi = inject(AdminApi);

  protected readonly titre = $localize`:@@rh.title:Conformité RH`;

  private readonly editions = resource({ loader: () => this.api.editions() });
  protected readonly liste = computed(() =>
    this.editions.hasValue() ? this.editions.value() : [],
  );
  protected readonly erreur = errorText(this.editions);
  private readonly choix = signal<string | null>(null);
  /** The edition read: the one picked, else the first the rights name. */
  protected readonly edition = computed(() => this.choix() ?? this.liste()[0]?.id ?? null);

  protected choisir(id: string): void {
    this.choix.set(id);
  }

  /** Ends the session — the provider's too, when it names a route for that. */
  protected async logout(): Promise<void> {
    let destination = '/login';
    try {
      destination = (await this.adminApi.logout()).urlDeconnexion ?? destination;
    } finally {
      window.location.assign(destination);
    }
  }
}
