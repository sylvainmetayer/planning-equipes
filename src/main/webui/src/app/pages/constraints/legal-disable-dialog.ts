// Confirmation asked before switching off a rule that founds the plan in law
// (« Légal (mineurs) », « Légal (temps de travail) »), in the organiser's
// safety policy for young workers (« Sécurité (mineurs) ») or in the meal rule
// the event is built on (« Organisation (repas) »).
//
// The other rules switch off without friction: they arbitrate comfort, and a
// plan that ignores one is merely worse. These are different — the solver then
// returns a plan scoring *zero hard* that nonetheless breaks the Code du
// travail, or holds a ten-hour day with no meal break, and nothing on the score
// says so. The dialog names the rule and what founds it, and restates what the
// conditions of use already say: the organiser stays the employer, the
// application is decision support.
//
// `legale` decides which consequence is stated. Only the two « Légal »
// categories rest on an article of the Code du travail; saying so of the
// minors' safety policy or of the meal break would over-claim, and a warning
// that over-claims is one an administrator learns to skip.
//
// Confirmation only. No reason typed, nothing journalled: migration V39
// removed exactly those columns because, with no authenticated user, the
// author could only ever be the constant « ui » — neither attributable nor
// usable. What stays visible is the state itself, permanently, on this very
// screen and on the Solveur one.

import { ChangeDetectionStrategy, Component, Injectable, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import {
  MAT_DIALOG_DATA,
  MatDialog,
  MatDialogModule,
  MatDialogRef,
} from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { ConstraintView } from '../../core/models';
import { LegalText } from '../../shared/legal-text';

export interface LegalDisableData {
  /** Technical name of the rule, as shown on its card. */
  name: string;
  /** Business description — for a legal rule, it is what cites the article of the Code du travail. */
  description: string;
  categorie: string;
  /** True only for the two « Légal (…) » categories — see the note at the top of this file. */
  legale: boolean;
}

@Component({
  selector: 'app-legal-disable-dialog',
  imports: [MatDialogModule, MatButtonModule, MatIconModule, RouterLink, LegalText],
  templateUrl: './legal-disable-dialog.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class LegalDisableDialog {
  protected readonly dialogRef = inject<MatDialogRef<LegalDisableDialog, boolean>>(MatDialogRef);
  protected readonly data = inject<LegalDisableData>(MAT_DIALOG_DATA);
}

/** Opens {@link LegalDisableDialog} as an awaitable boolean, like `ConfirmService`. */
@Injectable({ providedIn: 'root' })
export class LegalDisableConfirmService {
  private readonly dialog = inject(MatDialog);

  /**
   * True when the rule may be switched off: either it is not a protected one,
   * or the administrator confirmed. The check lives here, next to the dialog,
   * so no caller can switch a legal rule off by forgetting to ask.
   */
  async allowsDisabling(constraint: ConstraintView): Promise<boolean> {
    if (!constraint.protegee) {
      return true;
    }
    const dialogRef = this.dialog.open<LegalDisableDialog, LegalDisableData, boolean>(
      LegalDisableDialog,
      {
        data: {
          name: constraint.name,
          description: constraint.description,
          categorie: constraint.categorie,
          legale: constraint.legale,
        },
        width: '38rem',
        autoFocus: 'dialog',
      },
    );
    return (await firstValueFrom(dialogRef.afterClosed())) === true;
  }
}
