import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatRadioModule } from '@angular/material/radio';
import { MotifSignalement, NouveauSignalement } from '../../core/models';
import { motifLabel } from './espace-signalements';

/** A seat of the day that can still be reported: not over yet. */
export interface PosteSignalable {
  creneauId: number;
  standId: string;
  libelle: string;
}

export interface SignalementDialogData {
  /** ISO date of the day. */
  date: string;
  postes: PosteSignalable[];
  /** `jour`, or the key `creneauId|standId` of the seat the gesture came from. */
  choix: string;
}

/**
 * « Je ne pourrai pas venir » — two taps: the whole day or one seat, then an
 * optional reason from a closed list, never a free text field. Says, before the
 * click, that it changes nothing by itself: the organisation is told and
 * decides.
 */
@Component({
  selector: 'app-espace-signalement-dialog',
  imports: [DatePipe, MatButtonModule, MatDialogModule, MatRadioModule],
  template: `
    <h2 mat-dialog-title i18n="@@espace.signalement.titre">Je ne pourrai pas venir</h2>
    <mat-dialog-content>
      <p class="espace-signalement-jour">{{ data.date | date: 'EEEE d MMMM' }}</p>
      <mat-radio-group
        class="espace-signalement-choix"
        [value]="choix()"
        (change)="choix.set($event.value)"
        aria-label="Ce que vous signalez"
        i18n-aria-label="@@espace.signalement.choix.label"
      >
        <mat-radio-button value="jour" i18n="@@espace.signalement.choix.jour">Toute la journée</mat-radio-button>
        @for (poste of data.postes; track poste.creneauId + '|' + poste.standId) {
          <mat-radio-button [value]="poste.creneauId + '|' + poste.standId">{{ poste.libelle }}</mat-radio-button>
        }
      </mat-radio-group>
      <p class="espace-signalement-question" i18n="@@espace.signalement.motif.question">Pourquoi ? (facultatif)</p>
      <mat-radio-group
        class="espace-signalement-choix"
        [value]="motif()"
        (change)="motif.set($event.value)"
        aria-label="Motif"
        i18n-aria-label="@@espace.signalement.motif.label"
      >
        <mat-radio-button [value]="null" i18n="@@espace.signalement.motif.aucun">Je préfère ne pas préciser</mat-radio-button>
        @for (candidat of motifs; track candidat) {
          <mat-radio-button [value]="candidat">{{ libelleMotif(candidat) }}</mat-radio-button>
        }
      </mat-radio-group>
      <p class="espace-signalement-aide" i18n="@@espace.signalement.aide">Rien ne change à votre planning tant que l'organisation ne l'a pas constaté.</p>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button matButton mat-dialog-close i18n="@@espace.signalement.renoncer">Renoncer</button>
      <button matButton="filled" (click)="send()" i18n="@@espace.signalement.envoyer">Prévenir l'organisation</button>
    </mat-dialog-actions>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceSignalementDialog {
  protected readonly data = inject<SignalementDialogData>(MAT_DIALOG_DATA);
  private readonly ref = inject(MatDialogRef<EspaceSignalementDialog, NouveauSignalement>);

  protected readonly motifs: readonly MotifSignalement[] = ['PERSONNEL', 'TRANSPORT', 'AUTRE'];
  protected readonly choix = signal(this.data.choix);
  protected readonly motif = signal<MotifSignalement | null>(null);

  protected libelleMotif(motif: MotifSignalement): string {
    return motifLabel(motif);
  }

  protected send(): void {
    const choix = this.choix();
    const poste =
      choix === 'jour'
        ? undefined
        : this.data.postes.find((each) => `${each.creneauId}|${each.standId}` === choix);
    this.ref.close({
      portee: poste ? 'POSTE' : 'JOUR',
      date: this.data.date,
      creneauId: poste?.creneauId ?? null,
      standId: poste?.standId ?? null,
      motif: this.motif(),
    });
  }
}
