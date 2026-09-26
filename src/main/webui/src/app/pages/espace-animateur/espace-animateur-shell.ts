import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  inject,
  signal,
  viewChild,
  ViewEncapsulation,
} from '@angular/core';
import { takeUntilDestroyed, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatToolbarModule } from '@angular/material/toolbar';
import { MatTooltipModule } from '@angular/material/tooltip';
import {
  ActivatedRoute,
  NavigationEnd,
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from '@angular/router';
import { filter, map } from 'rxjs';
import { AdminApi } from '../../core/api/admin-api';
import { APP_CONFIG } from '../../core/app-config';
import { EspaceAnimateurService } from '../../core/espace-animateur.service';
import { PageFocusService } from '../../core/page-focus.service';
import { AppLocale, getStoredLocale, setStoredLocaleAndReload } from '../../core/locale';
import { signOut } from '../../core/session';
import { BrandLogo } from '../../shared/brand-logo';
import { VersionFooter } from '../../shared/version-footer';

/**
 * Standalone layout of the espace animateur (issue #165): a minimal toolbar
 * (no admin navigation, no solver monitor, no polling) above the two tabs —
 * the animateur's planning and their demandes d'échange. The access token in
 * the URL designates the fiche; a Keycloak session whose verified e-mail is
 * that fiche's, with the `animateur` role, is what opens it. This shell loads
 * everything from the token and the child pages read the shared
 * `EspaceAnimateurService` state.
 */
@Component({
  selector: 'app-espace-animateur-shell',
  imports: [
    DatePipe,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
    BrandLogo,
    VersionFooter,
    MatToolbarModule,
    MatButtonModule,
    MatCardModule,
    MatIconModule,
    MatMenuModule,
    MatProgressBarModule,
    MatTooltipModule,
  ],
  templateUrl: './espace-animateur-shell.html',
  styleUrls: ['../../../styles/espace-animateur.css', '../../../styles/demandes.css'],
  // Global by design (AGENTS.md): loaded with the route, unscoped like the partial it was.
  encapsulation: ViewEncapsulation.None,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class EspaceAnimateurShell {
  protected readonly espace = inject(EspaceAnimateurService);
  protected readonly locale: AppLocale = getStoredLocale();

  /**
   * Keycloak sign-in is on. Off (a deployment running on its break-glass
   * account alone), no session can open an espace: the screen says the espace
   * is unavailable rather than offering a door that does not exist.
   */
  protected readonly modeOidc = inject(APP_CONFIG).authOidc;

  /**
   * Set when the visitor is already signed in and the espace still refuses
   * them: a session without the `animateur` role, or whose e-mail is not the
   * one on the fiche this link designates. Without it the screen would loop —
   * "Se connecter" signs the same account straight back into the same refusal.
   */
  protected readonly signedInWithoutAccess = signal(false);

  private readonly adminApi = inject(AdminApi);
  private readonly route = inject(ActivatedRoute);
  protected readonly jeton = toSignal(
    this.route.paramMap.pipe(map((params) => params.get('jeton'))),
    {
      initialValue: null,
    },
  );

  private readonly router = inject(Router);
  private readonly pageFocus = inject(PageFocusService);
  private readonly contenu = viewChild<ElementRef<HTMLElement>>('contenu');

  constructor() {
    const jeton = this.route.snapshot.paramMap.get('jeton');
    if (jeton) {
      void this.espace.charger(jeton).then(() => this.diagnoseRefusal());
    }
    // Same contract as the admin shell: moving to another tab of the espace
    // hands the focus to <main> and speaks the new page's title, instead of
    // leaving it on the link just clicked with the whole toolbar to cross
    // again. Only a change of *page*: the planning's `?onglet=` and `?jour=`
    // are views of one page, and yanking the focus off the day strip on every
    // day picked would make it unusable.
    let cheminPrecedent: string | null = null;
    this.router.events
      .pipe(
        filter((event): event is NavigationEnd => event instanceof NavigationEnd),
        takeUntilDestroyed(),
      )
      .subscribe((event) => {
        const chemin = event.urlAfterRedirects.split(/[?#]/)[0];
        const changement = cheminPrecedent !== null && chemin !== cheminPrecedent;
        cheminPrecedent = chemin;
        if (changement) {
          queueMicrotask(() => this.announceNavigation());
        }
      });
  }

  private announceNavigation(): void {
    this.pageFocus.arriveOn(this.contenu()?.nativeElement);
  }

  /** Skip link: `href="#contenu"` alone would move the caret but not the focus. */
  protected focusContenu(event: Event): void {
    this.pageFocus.skipTo(event, this.contenu()?.nativeElement);
  }

  /** Who is signed in, if anyone — asked only once the espace has refused. */
  private async diagnoseRefusal(): Promise<void> {
    if (!this.modeOidc || !this.espace.authRequise()) {
      this.signedInWithoutAccess.set(false);
      return;
    }
    try {
      this.signedInWithoutAccess.set((await this.adminApi.session()).authentifie);
    } catch {
      // Unreachable probe: offering the sign-in button is the better guess.
      this.signedInWithoutAccess.set(false);
    }
  }

  /**
   * Signs in with Keycloak and comes back to this very espace: the token in
   * the URL is what designates the fiche — and so the edition — being opened.
   */
  protected signInWithKeycloak(): void {
    window.location.assign(this.adminApi.oidcLoginUrl(`/animateur/${this.jeton() ?? ''}`));
  }

  /**
   * Ends the session that does not open this espace — on Keycloak too, or the
   * next "Se connecter" would sign the same wrong account back in without
   * asking anything. The RP-initiated logout lands on the deployment's single
   * post-logout route; this espace is only the fallback without one.
   */
  protected deconnecter(): Promise<void> {
    return signOut(this.adminApi, window.location.pathname);
  }

  protected infobulleHorloge(date: string, heure: string): string {
    const moment = heure ? `${date} ${heure}` : date;
    return $localize`:@@espace.dateFigee:MOCK — la date du jour est figée au ${moment}:date: sur ce serveur : « Aujourd'hui » et les journées passées se lisent sur ce moment-là, pas sur celui du téléphone.`;
  }

  /** Language messages resolve once at bootstrap, so switching reloads the page. */
  protected toggleLocale(): void {
    setStoredLocaleAndReload(this.locale === 'fr' ? 'en' : 'fr');
  }
}
