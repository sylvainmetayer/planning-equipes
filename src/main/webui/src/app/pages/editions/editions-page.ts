import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ApiService } from '../../core/api.service';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { slugify } from '../../core/slug';
import { Edition } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PromptDialog } from '../../shared/prompt-dialog';

/**
 * Manages the editions the whole referential is partitioned into: create an
 * empty "Année 2026", duplicate "Année 2025" into it, rename one, designate
 * the fallback, delete one.
 *
 * Duplication is the action that makes several editions practical at all —
 * "2026 = 2025 minus the assignments" — so it is offered on every row rather
 * than buried behind the creation form. See `docs/editions.md` §6.
 */
@Component({
  selector: 'app-editions-page',
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
  templateUrl: './editions-page.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EditionsPage {
  protected readonly columns = ['nom', 'id', 'etat', 'actions'];
  protected readonly store = inject(EditionStore);

  /** Name typed in the creation form; its id is slugified from it, as on the découpage screen. */
  protected readonly nouveauNom = signal('');
  /** Id of the edition the new one should be a copy of, or `null` for an empty edition. */
  protected readonly sourceDuplication = signal<string | null>(null);
  protected readonly enCours = signal(false);

  protected readonly courantId = computed(() => this.store.courant()?.id ?? null);

  private readonly api = inject(ApiService);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

  constructor() {
    void this.recharger();
  }

  protected async creer(): Promise<void> {
    const nom = this.nouveauNom().trim();
    if (!nom || this.enCours()) {
      return;
    }
    const cible: Pick<Edition, 'id' | 'nom'> = {
      id: slugify(nom, this.store.editions().map((edition) => edition.id)),
      nom
    };
    const source = this.sourceDuplication();
    const url = source ? `/api/editions/${encodeURIComponent(source)}/dupliquer` : '/api/editions';
    await this.executer(async () => {
      await this.api.post<Edition>(url, cible);
      this.nouveauNom.set('');
      this.sourceDuplication.set(null);
      this.notifications.notify({
        title: source
          ? $localize`:@@editions.duplicated:Édition ${nom}:nom: créée à partir de ${source}:source:.`
          : $localize`:@@editions.created:Édition ${nom}:nom: créée.`,
        variant: 'success',
        timeout: 4000
      });
    });
  }

  protected async renommer(edition: Edition, nom: string): Promise<void> {
    const nouveau = nom.trim();
    if (!nouveau || nouveau === edition.nom) {
      return;
    }
    await this.executer(() => this.api.put(`/api/editions/${encodeURIComponent(edition.id)}`, { nom: nouveau }));
  }

  protected async definirParDefaut(edition: Edition): Promise<void> {
    await this.executer(() => this.api.put(`/api/editions/${encodeURIComponent(edition.id)}/defaut`, {}));
  }

  protected basculer(edition: Edition): void {
    this.store.basculer(edition);
  }

  /**
   * Deleting an edition takes a whole festival with it — referential, settings
   * and solved plan — and nothing restores it. So this is the one action in
   * the application that asks the user to type the name rather than to click
   * once: the friction is the point, exactly as when deleting a repository.
   */
  protected async supprimer(edition: Edition): Promise<void> {
    const saisi = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@editions.delete.title:Supprimer l'édition ${edition.nom}:nom: ?`,
      label: $localize`:@@editions.delete.typeName:Saisissez « ${edition.nom}:nom: » pour confirmer`,
      confirmLabel: $localize`:@@common.delete:Supprimer`
    });
    if (saisi === null) {
      return;
    }
    if (saisi.trim() !== edition.nom.trim()) {
      this.notifications.notify({
        title: $localize`:@@editions.delete.mismatch:Nom incorrect : l'édition n'a pas été supprimée.`,
        variant: 'error'
      });
      return;
    }
    await this.executer(() => this.api.delete(`/api/editions/${encodeURIComponent(edition.id)}`));
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
