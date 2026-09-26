import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  OnInit,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatCardModule } from '@angular/material/card';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';
import { EditionsApi } from '../../core/api/editions-api';
import { EditionStore } from '../../core/edition.store';
import { NotificationService } from '../../core/notification.service';
import { Edition } from '../../core/models';
import { ConfirmService } from '../../shared/confirm-dialog';
import { PromptDialog } from '../../shared/prompt-dialog';
import { errorMessage } from '../../core/error-message';
import { EmptyEditionCard } from './empty-edition-card';

/** The shape of an edition id (`E1`, `E2`…), which the server refuses as a name. */
const NOM_FORME_ID = /^[Ee][1-9][0-9]*$/;

/**
 * Manages the editions the whole referential is partitioned into: create an
 * empty "Année 2026", duplicate "Année 2025" into it, rename one, designate
 * the fallback, delete one — and empty the one this tab works in.
 *
 * Duplication is the action that makes several editions practical at all —
 * "2026 = 2025 minus the assignments" — so it is offered on every row rather
 * than buried behind the creation form. See `docs/decisions/0001-cloisonnement-par-edition.md` §6.
 */
@Component({
  selector: 'app-editions-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatCheckboxModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatTableModule,
    MatTooltipModule,
    EmptyEditionCard,
  ],
  templateUrl: './editions-page.html',
  styleUrl: './editions-page.css',
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EditionsPage implements OnInit {
  protected readonly columns = ['nom', 'id', 'etat', 'actions'];
  protected readonly store = inject(EditionStore);

  /** Name typed in the creation form; the server draws the id. */
  protected readonly nouveauNom = signal('');
  /** Id of the edition the new one should be a copy of, or `null` for an empty edition. */
  protected readonly sourceDuplication = signal<string | null>(null);
  /**
   * Whether a duplication brings the people along (issue #90). On by default,
   * which is the gesture of issue #172 — a « plan canicule » duplicated
   * mid-festival keeps its roster. Turned off, the copy is a year template:
   * the structure, nobody.
   */
  protected readonly keepAnimateurs = signal(true);
  protected readonly enCours = signal(false);

  protected readonly courantId = computed(() => this.store.courant()?.id ?? null);

  private readonly editionsApi = inject(EditionsApi);
  private readonly notifications = inject(NotificationService);
  private readonly confirm = inject(ConfirmService);
  private readonly dialog = inject(MatDialog);

  ngOnInit(): void {
    void this.recharger();
  }

  protected async creer(): Promise<void> {
    const nom = this.nouveauNom().trim();
    if (!nom || this.enCours() || this.refuseIdShapedName(nom)) {
      return;
    }
    const sourceId = this.sourceDuplication();
    // Named by what the user picked in the list, not by its id.
    const source =
      this.store.editions().find((edition) => edition.id === sourceId)?.nom ?? sourceId;
    await this.executer(async () => {
      const keepAnimateurs = this.keepAnimateurs();
      await this.editionsApi.create(nom, sourceId, keepAnimateurs);
      this.nouveauNom.set('');
      this.sourceDuplication.set(null);
      this.keepAnimateurs.set(true);
      let title = $localize`:@@editions.created:Édition ${nom}:nom: créée.`;
      if (source) {
        title = keepAnimateurs
          ? $localize`:@@editions.duplicated:Édition ${nom}:nom: créée à partir de ${source}:source:.`
          : $localize`:@@editions.duplicatedSansAnimateurs:Édition ${nom}:nom: créée à partir de ${source}:source:, sans les animateurs.`;
      }
      this.notifications.notify({
        title,
        variant: 'success',
        timeout: 4000,
      });
    });
  }

  protected async renommer(edition: Edition, nom: string): Promise<void> {
    const nouveau = nom.trim();
    if (!nouveau || nouveau === edition.nom || this.refuseIdShapedName(nouveau)) {
      return;
    }
    await this.executer(() => this.editionsApi.rename(edition.id, nouveau));
  }

  protected async definirParDefaut(edition: Edition): Promise<void> {
    await this.executer(() => this.editionsApi.setDefault(edition.id));
  }

  protected basculer(edition: Edition): void {
    this.store.basculer(edition);
  }

  /**
   * Deleting an edition takes a whole event with it — referential, settings
   * and solved plan — and nothing restores it. So this is the one action in
   * the application that asks the user to type the name rather than to click
   * once: the friction is the point, exactly as when deleting a repository.
   */
  protected async supprimer(edition: Edition): Promise<void> {
    const saisi = await PromptDialog.ask(this.dialog, {
      title: $localize`:@@editions.delete.title:Supprimer l'édition ${edition.nom}:nom: ?`,
      label: $localize`:@@editions.delete.typeName:Saisissez « ${edition.nom}:nom: » pour confirmer`,
      confirmLabel: $localize`:@@common.delete:Supprimer`,
      // Deleting a whole edition is more destructive than emptying it: the
      // button reads as such, like the two other typed-back confirmations.
      danger: true,
    });
    if (saisi === null) {
      return;
    }
    if (saisi.trim() !== edition.nom.trim()) {
      this.notifications.notify({
        title: $localize`:@@editions.delete.mismatch:Nom incorrect : l'édition n'a pas été supprimée.`,
        variant: 'error',
      });
      return;
    }
    await this.executer(() => this.editionsApi.delete(edition.id));
  }

  /**
   * « E2 » reads as an edition id, and the server refuses it as a name: said
   * here first, before a round trip ends in a generic refusal.
   */
  private refuseIdShapedName(nom: string): boolean {
    if (!NOM_FORME_ID.test(nom)) {
      return false;
    }
    this.notifications.notify({
      title: $localize`:@@editions.nomFormeId:« ${nom}:nom: » a la forme d'un identifiant d'édition : choisissez un autre nom.`,
      variant: 'error',
    });
    return true;
  }

  private async executer(action: () => Promise<unknown>): Promise<void> {
    this.enCours.set(true);
    try {
      await action();
      await this.store.reload();
    } catch (error) {
      this.notifications.notify({
        title: errorMessage(error),
        variant: 'error',
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
        title: errorMessage(error),
        variant: 'error',
      });
    }
  }
}
