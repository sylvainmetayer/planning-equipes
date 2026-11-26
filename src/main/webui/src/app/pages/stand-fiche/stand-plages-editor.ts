import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { StandsApi } from '../../core/api/stands-api';
import { ModeHoraire, PlageStand, WrittenStand } from '../../core/models';
import { NotificationService } from '../../core/notification.service';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { SessionStore } from '../../core/session.store';
import { ConfirmService } from '../../shared/confirm-dialog';
import { HhmmPipe } from '../../shared/hhmm-pipe';
import {
  MAX_PLAGES_AFFICHEES,
  PlageForm,
  emptyPlageForm,
  plageDays,
  plageFormError,
  plageOfForm,
  plagesOfDay,
  sortedPlages,
} from './stand-plages';

/**
 * « Plages datées » of the fiche stand (ADR 0104, 0124): the one list the
 * solver reads, a line per dated window, opening or closure, with its origin —
 * « modèle » when the server unfolded it from the recurring model, « saisie »
 * when somebody typed it. A plage is added by hand, or removed one at a time or
 * a whole day at a time; every write goes through the stand's plage routes,
 * which the server refuses during a solve and under the STANDS freeze. The list
 * can be narrowed to one day, which the page keeps in its address.
 */
@Component({
  selector: 'app-stand-plages-editor',
  imports: [
    FormsModule,
    HhmmPipe,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './stand-plages-editor.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StandPlagesEditor {
  readonly standId = input.required<string>();
  readonly plages = input.required<readonly PlageStand[]>();
  readonly effectifMax = input(0);
  /** The day the list is narrowed to, empty for all of them. */
  readonly jour = input('');
  /** A solve running, or a STANDS freeze: the list reads, it does not write. */
  readonly locked = input(false);
  readonly jourChange = output<string>();
  /** A plage was written: the page reads the stand again. */
  readonly changed = output<void>();

  private readonly standsApi = inject(StandsApi);
  private readonly notifications = inject(NotificationService);
  private readonly crud = inject(ReferenceCrudService);
  private readonly confirm = inject(ConfirmService);
  protected readonly lectureSeule = inject(SessionStore).lectureSeule;

  protected readonly sorted = computed(() => sortedPlages(this.plages()));
  protected readonly days = computed(() => plageDays(this.plages()));
  protected readonly filtered = computed(() => plagesOfDay(this.sorted(), this.jour()));
  /** Without a day chosen, a long list is cut: the day filter is the way in. */
  protected readonly all = signal(false);
  protected readonly shown = computed(() =>
    this.jour() || this.all() ? this.filtered() : this.filtered().slice(0, MAX_PLAGES_AFFICHEES),
  );
  protected readonly cut = computed(() => this.filtered().length - this.shown().length);
  protected readonly modelCount = computed(
    () => this.filtered().filter((plage) => plage.depliee).length,
  );

  protected readonly form = signal<PlageForm>(emptyPlageForm());
  protected readonly formError = computed(() => plageFormError(this.form(), this.effectifMax()));
  protected readonly busy = signal(false);

  protected patch(patch: Partial<PlageForm>): void {
    this.form.update((form) => ({ ...form, ...patch }));
  }

  protected setMode(mode: ModeHoraire): void {
    this.patch({ mode });
  }

  protected modeLabel(mode: ModeHoraire): string {
    return mode === 'OUVERTURE'
      ? $localize`:@@standFiche.plages.mode.ouverture:Ouverture`
      : $localize`:@@standFiche.plages.mode.fermeture:Fermeture`;
  }

  protected originLabel(plage: PlageStand): string {
    return plage.depliee
      ? $localize`:@@standFiche.plages.origine.modele:modèle`
      : $localize`:@@standFiche.plages.origine.saisie:saisie`;
  }

  protected originTip(plage: PlageStand): string {
    return plage.depliee
      ? $localize`:@@standFiche.plages.origine.modele.aide:Posée par le modèle récurrent : un nouvel enregistrement du modèle la remplace.`
      : $localize`:@@standFiche.plages.origine.saisie.aide:Saisie à la main : le modèle n'y touche pas.`;
  }

  protected get closing(): string {
    return $localize`:@@standFiche.plages.fermeture:fermeture`;
  }

  protected async add(): Promise<void> {
    if (this.formError() !== null || this.busy() || this.locked()) {
      return;
    }
    const form = this.form();
    if (await this.write(() => this.standsApi.addPlage(this.standId(), plageOfForm(form)))) {
      // The next plage is most often on the same day: only the hours are cleared.
      this.form.set({ ...emptyPlageForm(form.date), mode: form.mode });
    }
  }

  protected async remove(plage: PlageStand): Promise<void> {
    if (plage.id === null || this.busy() || this.locked()) {
      return;
    }
    const id = plage.id;
    await this.write(() => this.standsApi.removePlage(this.standId(), id));
  }

  protected async removeDay(): Promise<void> {
    const jour = this.jour();
    if (!jour || this.busy() || this.locked()) {
      return;
    }
    const sure = await this.confirm.ask({
      title: $localize`:@@standFiche.plages.jour.titre:Retirer toutes les plages de ce jour ?`,
      message: $localize`:@@standFiche.plages.jour.message:Le ${jour}:date:, le stand redevient ouvert toute la journée. Les plages posées par le modèle ne reviennent pas d'elles-mêmes.`,
      confirmLabel: $localize`:@@standFiche.plages.jour.confirmer:Retirer le jour`,
      danger: true,
    });
    if (sure && (await this.write(() => this.standsApi.removePlagesOfDay(this.standId(), jour)))) {
      this.jourChange.emit('');
    }
  }

  /** A write of the plage routes: its warnings said as the stand form says them, its refusal reported. */
  private async write(call: () => Promise<WrittenStand>): Promise<boolean> {
    this.busy.set(true);
    try {
      const written = await call();
      if (written.avertissements.length > 0) {
        this.notifications.notify({
          title: $localize`:@@standFiche.plages.avertissements:Plages enregistrées, des points sont à vérifier.`,
          message: written.avertissements.map((avertissement) => avertissement.message).join(' · '),
          variant: 'warning',
          timeout: 0,
        });
      }
      this.changed.emit();
      return true;
    } catch (error) {
      this.crud.reportError(error);
      return false;
    } finally {
      this.busy.set(false);
    }
  }
}
