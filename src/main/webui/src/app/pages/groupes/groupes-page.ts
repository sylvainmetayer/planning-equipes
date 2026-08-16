import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { GroupeStore } from '../../core/groupe.store';
import { NotificationService } from '../../core/notification.service';
import { slugify } from '../../core/slug';
import { Groupe } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';

/**
 * Manages the editions (`groupe`) the whole referential is partitioned into:
 * create an empty "Année 2026", duplicate "Année 2025" into it, rename one,
 * designate the fallback, delete one.
 *
 * Duplication is the action that makes several editions practical at all —
 * "2026 = 2025 minus the assignments" — so it is offered on every row rather
 * than buried behind the creation form. See `docs/groupes.md` §6.
 */
@Component({
  selector: 'app-groupes-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTableModule,
    MatTooltipModule
  ],
  templateUrl: './groupes-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class GroupesPage {
  protected readonly columns = ['nom', 'id', 'etat', 'actions'];
  protected readonly store = inject(GroupeStore);

  /** Name typed in the creation form; its id is slugified from it, as on the découpage screen. */
  protected readonly nouveauNom = signal('');
  /** Id of the group the new one should be a copy of, or `null` for an empty group. */
  protected readonly sourceDuplication = signal<string | null>(null);
  protected readonly enCours = signal(false);

  protected readonly courantId = computed(() => this.store.courant()?.id ?? null);

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);

  constructor() {
    void this.recharger();
  }

  protected async creer(): Promise<void> {
    const nom = this.nouveauNom().trim();
    if (!nom || this.enCours()) {
      return;
    }
    const cible: Pick<Groupe, 'id' | 'nom'> = {
      id: slugify(nom, this.store.groupes().map((groupe) => groupe.id)),
      nom
    };
    const source = this.sourceDuplication();
    const url = source ? `/api/groupes/${encodeURIComponent(source)}/dupliquer` : '/api/groupes';
    await this.executer(async () => {
      await this.api.post<Groupe>(url, cible);
      this.nouveauNom.set('');
      this.sourceDuplication.set(null);
      this.notifications.notify({
        title: source
          ? $localize`:@@groupes.duplicated:Groupe ${nom}:nom: créé à partir de ${source}:source:.`
          : $localize`:@@groupes.created:Groupe ${nom}:nom: créé.`,
        variant: 'success',
        timeout: 4000
      });
    });
  }

  protected async renommer(groupe: Groupe, nom: string): Promise<void> {
    const nouveau = nom.trim();
    if (!nouveau || nouveau === groupe.nom) {
      return;
    }
    await this.executer(() => this.api.put(`/api/groupes/${encodeURIComponent(groupe.id)}`, { nom: nouveau }));
  }

  protected async definirParDefaut(groupe: Groupe): Promise<void> {
    await this.executer(() => this.api.put(`/api/groupes/${encodeURIComponent(groupe.id)}/defaut`, {}));
  }

  protected basculer(groupe: Groupe): void {
    this.store.basculer(groupe);
  }

  /** Explicit confirmation: deleting a group takes its whole referential with it. */
  protected async supprimer(groupe: Groupe): Promise<void> {
    const confirme = await this.confirm.ask({
      title: $localize`:@@groupes.delete.title:Supprimer le groupe ${groupe.nom}:nom: ?`,
      message: $localize`:@@groupes.delete.message:Tout son référentiel — stands, animateurs, typologies, créneaux, paramètres et planning résolu — est supprimé définitivement.`,
      confirmLabel: $localize`:@@common.delete:Supprimer`,
      danger: true
    });
    if (!confirme) {
      return;
    }
    await this.executer(() => this.api.delete(`/api/groupes/${encodeURIComponent(groupe.id)}`));
  }

  private async executer(action: () => Promise<unknown>): Promise<void> {
    this.enCours.set(true);
    try {
      await action();
      await this.store.reload();
    } catch (error) {
      this.notifications.notify({
        title: error instanceof Error ? error.message : String(error),
        variant: 'error'
      });
    } finally {
      this.enCours.set(false);
    }
  }

  private async recharger(): Promise<void> {
    try {
      await this.store.reload();
    } catch (error) {
      this.notifications.notify({
        title: error instanceof Error ? error.message : String(error),
        variant: 'error'
      });
    }
  }
}
