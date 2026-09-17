import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ConstraintsApi } from '../../core/api/constraints-api';
import { errorPrefix } from '../../core/error-message';
import { ParametresQualite } from '../../core/models';

/**
 * The organisational-quality thresholds of the edition: how many emplacements
 * and how many typologies one animateur may spread over, and what counts as a
 * late closing followed by an early opening.
 *
 * <p>These used to be deployment configuration, identical for every edition and
 * shown nowhere (issue #591): an organiser for whom three typologies is normal
 * could only dose the rule's weight until it stopped mattering. They are stored
 * per edition now, and what the server configures is what an edition that never
 * opened this card solves with.</p>
 *
 * <p>Nothing here has a legal floor, unlike the card above — these arbitrate
 * comfort against comfort. Leaving both hours empty is a legitimate answer: it
 * is how `eviterFermeturePuisOuverture` is silenced.</p>
 */
@Component({
  selector: 'app-parametres-qualite',
  imports: [
    FormsModule,
    MatCardModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  templateUrl: './parametres-qualite.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ParametresQualiteCard {
  protected readonly loading = signal(false);
  protected readonly error = signal('');
  protected readonly saved = signal(false);

  protected readonly dailyLocationsCap = signal<number | null>(null);
  protected readonly typologiesDistinctesMax = signal<number | null>(null);
  protected readonly heureServiceTardif = signal('');
  protected readonly heureServiceMatinal = signal('');
  protected readonly restAfterLateServiceHours = signal<number | null>(null);

  private readonly constraintsApi = inject(ConstraintsApi);

  constructor() {
    void this.load();
  }

  protected async load(): Promise<void> {
    this.loading.set(true);
    this.error.set('');
    try {
      this.read(await this.constraintsApi.qualityParameters());
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }

  private read(parametres: ParametresQualite): void {
    this.dailyLocationsCap.set(parametres.maxEmplacementsDistinctsParJour ?? null);
    this.typologiesDistinctesMax.set(parametres.typologiesDistinctesMax ?? null);
    // `HH:mm:ss` on the wire, `HH:mm` in a time input: the seconds are always
    // zero here and an input that shows them asks for a value nobody means.
    this.heureServiceTardif.set(heureCourte(parametres.heureServiceTardif));
    this.heureServiceMatinal.set(heureCourte(parametres.heureServiceMatinal));
    this.restAfterLateServiceHours.set(
      (parametres.reposSouhaiteApresServiceTardifMinutes ?? 0) / 60,
    );
  }

  /**
   * Saves the five thresholds as one record, like the legal card: the server
   * replaces the whole row, so a partial save would silently reset what this
   * card did not show.
   */
  protected async save(): Promise<void> {
    const emplacements = this.dailyLocationsCap();
    const typologies = this.typologiesDistinctesMax();
    const reposHeures = this.restAfterLateServiceHours();
    if (
      emplacements === null ||
      emplacements < 1 ||
      typologies === null ||
      typologies < 1 ||
      reposHeures === null ||
      reposHeures < 0
    ) {
      return;
    }
    // Both hours or neither: one alone describes no pair, and the server
    // refuses it rather than storing a rule that could never fire.
    if ((this.heureServiceTardif() === '') !== (this.heureServiceMatinal() === '')) {
      this.error.set(
        $localize`:@@parametres.qualite.error.heuresIncompletes:Indiquez les deux heures de service, ou aucune : une seule ne décrit aucune paire fermeture/ouverture.`,
      );
      return;
    }
    this.loading.set(true);
    this.error.set('');
    this.saved.set(false);
    try {
      this.read(
        await this.constraintsApi.saveQualityParameters({
          maxEmplacementsDistinctsParJour: Math.round(emplacements),
          typologiesDistinctesMax: Math.round(typologies),
          heureServiceTardif: this.heureServiceTardif() || null,
          heureServiceMatinal: this.heureServiceMatinal() || null,
          reposSouhaiteApresServiceTardifMinutes: Math.round(reposHeures * 60),
        }),
      );
      this.saved.set(true);
    } catch (error) {
      this.error.set(errorPrefix(error));
    } finally {
      this.loading.set(false);
    }
  }
}

/** `HH:mm:ss` or `HH:mm` from the server, as the `HH:mm` a time input accepts. */
function heureCourte(heure: string | null | undefined): string {
  return heure ? heure.slice(0, 5) : '';
}
