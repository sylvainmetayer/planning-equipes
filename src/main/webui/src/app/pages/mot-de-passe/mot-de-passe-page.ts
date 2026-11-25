import {
  afterNextRender,
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
  computed,
  inject,
  signal,
  ViewEncapsulation,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { AdminApi } from '../../core/api/admin-api';
import { BRANDING } from '../../core/branding';
import { errorMessage } from '../../core/error-message';
import { BrandLogo } from '../../shared/brand-logo';
import { StatusMessage } from '../../shared/status-message';

/** The server's own floor (`MotDePasse.LONGUEUR_MIN`): said here so the button waits for it. */
const LONGUEUR_MIN = 12;

/**
 * « Choisir mon mot de passe » (ADR 0094): the page the mailed single-use link
 * opens, for an invitation (72 h) as for a reset (1 h). The token rides in the
 * **fragment** (`/mot-de-passe#<token>`) — never sent to a server, a proxy log
 * or a `Referer` — and goes back in the body of the one call this page makes.
 * Outside both shells, like `/login`: whoever opens it has no session yet.
 */
@Component({
  selector: 'app-mot-de-passe-page',
  imports: [
    BrandLogo,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    RouterLink,
    StatusMessage,
  ],
  templateUrl: './mot-de-passe-page.html',
  // Reuses the login card's layout, which is global by design.
  styleUrl: '../login/login-page.css',
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class ChoosePasswordPage {
  protected readonly productName = inject(BRANDING).productName;

  private readonly api = inject(AdminApi);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  private readonly injector = inject(Injector);
  /** `null` when the link was opened without its token: nothing to submit. */
  protected readonly jeton = inject(ActivatedRoute).snapshot.fragment;

  protected readonly longueurMin = LONGUEUR_MIN;
  protected readonly password = signal('');
  protected readonly confirmation = signal('');
  protected readonly busy = signal(false);
  protected readonly erreur = signal<string | null>(null);
  protected readonly choisi = signal(false);

  constructor() {
    // The token leaves the address bar at once: it would otherwise stay in the
    // history, a bookmark, a screenshot, and travel in the error reports.
    if (this.jeton) {
      window.history.replaceState(
        window.history.state,
        '',
        window.location.pathname + window.location.search,
      );
    }
  }

  /** In code points of the NFC form, as the server counts. */
  private length(value: string): number {
    return [...value.normalize('NFC')].length;
  }

  /** Why the button waits, said in words — a disabled button alone explains nothing. */
  protected readonly raison = computed(() => {
    const count = this.length(this.password());
    if (count < LONGUEUR_MIN) {
      return $localize`:@@motDePasse.court:Encore ${LONGUEUR_MIN - count}:manque: caractère(s) au moins.`;
    }
    if (this.password().normalize('NFC') !== this.confirmation().normalize('NFC')) {
      return $localize`:@@motDePasse.differents:Les deux mots de passe ne sont pas identiques.`;
    }
    return '';
  });

  protected readonly valid = computed(() => this.raison() === '');

  protected async choisir(event: Event): Promise<void> {
    event.preventDefault();
    if (this.busy() || !this.valid() || !this.jeton) {
      return;
    }
    this.busy.set(true);
    this.erreur.set(null);
    try {
      await this.api.choosePassword(this.jeton, this.password());
      this.choisi.set(true);
      afterNextRender(
        () => this.host.nativeElement.querySelector<HTMLElement>('.mdp-fait')?.focus(),
        {
          injector: this.injector,
        },
      );
    } catch (error) {
      this.erreur.set(errorMessage(error));
    } finally {
      this.busy.set(false);
    }
  }
}
