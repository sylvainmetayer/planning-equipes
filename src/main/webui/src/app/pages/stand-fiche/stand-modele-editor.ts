import {
  ChangeDetectionStrategy,
  Component,
  computed,
  inject,
  input,
  linkedSignal,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { joursEdition } from '../../core/horaire-stand';
import { Stand } from '../../core/models';
import { ReferenceCrudService } from '../../core/reference-crud.service';
import { ReferenceDataStore } from '../../core/reference-data.store';
import { SessionStore } from '../../core/session.store';
import { HoraireReglesEditor } from '../stands/horaire-regles-editor';
import { HoraireDraft, normaliserHoraire, toDraft } from '../stands/stand-draft';
import { premiereErreurHoraire } from '../stands/stand-horaires';

/**
 * « Modèle récurrent » of the fiche stand (ADR 0104): the rule the stand keeps
 * — « samedi et dimanche de 10:00 à 18:00, effectif 2 » —, edited in place with
 * the rules editor of the stand form. Saving it writes the stand, and the
 * server unfolds the model into plages on the days of the event; the plages
 * typed by hand stay. The save goes through the CRUD service: the stand's stamp
 * is its precondition, and a concurrent write is answered by its three-way
 * dialog.
 */
@Component({
  selector: 'app-stand-modele-editor',
  imports: [FormsModule, HoraireReglesEditor, MatButtonModule, MatIconModule],
  templateUrl: './stand-modele-editor.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class StandModeleEditor {
  readonly stand = input.required<Stand>();
  /** A solve running, or a STANDS freeze: the rules read, they do not write. */
  readonly locked = input(false);
  /** The model was written: the page reads the stand again. */
  readonly saved = output<void>();

  private readonly store = inject(ReferenceDataStore);
  private readonly crud = inject(ReferenceCrudService);
  protected readonly lectureSeule = inject(SessionStore).lectureSeule;

  /** The stored model as text: the draft starts over only when the stored model itself moved. */
  private readonly stored = computed(() => JSON.stringify(this.stand().horaires ?? []));
  protected readonly horaires = linkedSignal<string, HoraireDraft[]>({
    source: this.stored,
    computation: () => toDraft(this.stand()).horaires,
  });
  /** Rules typed and not saved: the page asks before it is left. */
  readonly modified = linkedSignal<string, boolean>({
    source: this.stored,
    computation: () => false,
  });
  protected readonly saving = signal(false);

  protected readonly jours = computed(() => joursEdition(this.store.creneaux()));
  protected readonly error = computed(() =>
    premiereErreurHoraire(this.horaires(), Number(this.stand().effectifMax)),
  );

  protected change(horaires: HoraireDraft[]): void {
    this.horaires.set(horaires);
    this.modified.set(true);
  }

  protected reset(): void {
    this.horaires.set(toDraft(this.stand()).horaires);
    this.modified.set(false);
  }

  protected async save(): Promise<void> {
    const stand = this.stand();
    if (!this.modified() || this.saving() || this.locked() || this.error() !== null) {
      return;
    }
    this.saving.set(true);
    try {
      const written = await this.crud.save(
        'stands',
        { ...stand, horaires: this.horaires().map(normaliserHoraire) },
        stand.id,
        $localize`:@@stands.entityLabel:Stand`,
        { text: stand.nom },
      );
      if (written) {
        this.modified.set(false);
        this.saved.emit();
      }
    } finally {
      this.saving.set(false);
    }
  }
}
