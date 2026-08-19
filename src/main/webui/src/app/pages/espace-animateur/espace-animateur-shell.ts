import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { ActivatedRoute, RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';
import { map } from 'rxjs';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { AppLocale, getStoredLocale, setStoredLocaleAndReload } from '../../core/locale';

/**
 * Standalone layout of the espace animateur (issue #165): a minimal toolbar
 * (no admin navigation, no solver monitor, no polling) above the two tabs —
 * the animateur's planning and their demandes d'échange. The access token in
 * the URL is the whole credential: this shell loads everything from it and
 * the child pages read the shared `EspaceAnimateurService` state.
 */
@Component({
  selector: 'app-espace-animateur-shell',
  imports: [
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    MatToolbarModule,
    MatButtonModule,
    MatIconModule,
    MatProgressBarModule
  ],
  templateUrl: './espace-animateur-shell.html',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class EspaceAnimateurShell {
  protected readonly espace = inject(EspaceAnimateurService);
  protected readonly locale: AppLocale = getStoredLocale();

  private readonly route = inject(ActivatedRoute);
  protected readonly jeton = toSignal(this.route.paramMap.pipe(map((params) => params.get('jeton'))), {
    initialValue: null
  });

  constructor() {
    const jeton = this.route.snapshot.paramMap.get('jeton');
    if (jeton) {
      void this.espace.charger(jeton);
    }
  }

  /** Language messages resolve once at bootstrap, so switching reloads the page. */
  protected toggleLocale(): void {
    setStoredLocaleAndReload(this.locale === 'fr' ? 'en' : 'fr');
  }
}
