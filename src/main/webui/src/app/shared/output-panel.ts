import { Component, input } from '@angular/core';
import { MatCardModule } from '@angular/material/card';

/** Monospaced result panel shared by the action pages. */
@Component({
  selector: 'app-output-panel',
  imports: [MatCardModule],
  template: `
    @if (text()) {
      <mat-card appearance="outlined" class="output-panel">
        <mat-card-content>
          <pre>{{ text() }}</pre>
        </mat-card-content>
      </mat-card>
    }
  `
})
export class OutputPanel {
  readonly text = input('');
}
