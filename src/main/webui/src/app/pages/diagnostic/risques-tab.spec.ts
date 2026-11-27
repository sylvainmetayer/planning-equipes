// The Risques tab's landing: `section=` is obeyed once the sections above the
// target have their figures, then the focus follows the shell's own. The three
// hosted screens are replaced by stand-ins that only say whether they load.

import { Location } from '@angular/common';
import {
  Component,
  forwardRef,
  input,
  provideZonelessChangeDetection,
  signal,
} from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, NavigationEnd, Router } from '@angular/router';
import { BehaviorSubject, Subject } from 'rxjs';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { FormationPage } from '../formation/formation-page';
import { FragilitePage } from '../fragilite/fragilite-page';
import { TensionTab } from '../marge/tension-tab';
import { RisquesTab } from './risques-tab';

@Component({
  selector: 'app-fragilite-page',
  template: '',
  providers: [{ provide: FragilitePage, useExisting: forwardRef(() => FragiliteStub) }],
})
class FragiliteStub {
  readonly entete = input(true);
  readonly chargement = signal(true);
}

@Component({
  selector: 'app-formation-page',
  template: '',
  providers: [{ provide: FormationPage, useExisting: forwardRef(() => FormationStub) }],
})
class FormationStub {
  readonly chargement = signal(true);
}

@Component({
  selector: 'app-tension-tab',
  template: '<h2 id="tension-titre">Tension</h2>',
  providers: [{ provide: TensionTab, useExisting: forwardRef(() => TensionStub) }],
})
class TensionStub {
  readonly loading = signal(true);
}

describe('RisquesTab landing', () => {
  const events = new Subject<unknown>();
  const navigate = vi.fn(() => Promise.resolve(true));
  const scrolled: string[] = [];

  beforeEach(() => {
    scrolled.length = 0;
    navigate.mockClear();
    Element.prototype.scrollIntoView = function (this: Element) {
      scrolled.push(this.id);
    };
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        {
          provide: ActivatedRoute,
          useValue: {
            queryParamMap: new BehaviorSubject(
              convertToParamMap({ onglet: 'risques', section: 'tension' }),
            ),
          },
        },
        { provide: Router, useValue: { events, navigate } },
        {
          provide: Location,
          useValue: { path: () => '/diagnostic?onglet=risques&section=tension' },
        },
      ],
    });
    TestBed.overrideComponent(RisquesTab, {
      set: { imports: [FragiliteStub, FormationStub, TensionStub] },
    });
  });

  afterEach(() => vi.useRealTimers());

  it('waits for the sections above, scrolls, drops the param, then focuses the section', async () => {
    const fixture = TestBed.createComponent(RisquesTab);
    await fixture.whenStable();
    const stub = <T>(type: new (...args: never[]) => T): T =>
      fixture.debugElement.query((el) => el.componentInstance instanceof type).componentInstance;

    // Fragilité is still loading: Tension, below it, would be pushed down.
    stub(FormationStub).chargement.set(false);
    await fixture.whenStable();
    expect(scrolled).toEqual([]);

    // Tension's own loading does not matter: nothing sits under it.
    stub(FragiliteStub).chargement.set(false);
    await fixture.whenStable();
    await vi.waitFor(() => expect(navigate).toHaveBeenCalledOnce());
    expect(scrolled).toEqual(['risques-tension']);
    expect(navigate.mock.calls[0]).toEqual([
      [],
      expect.objectContaining({ queryParams: { onglet: 'risques' }, replaceUrl: true }),
    ]);

    const section = (fixture.nativeElement as HTMLElement).querySelector('#risques-tension');
    // The shell's own focus runs on that end too, a microtask: ours comes after it.
    vi.useFakeTimers();
    const landing = '/diagnostic?onglet=risques&section=tension';
    events.next(new NavigationEnd(1, landing, landing));
    vi.runAllTimers();
    expect(document.activeElement).not.toBe(section);
    events.next(new NavigationEnd(1, '/diagnostic?onglet=risques', '/diagnostic?onglet=risques'));
    expect(document.activeElement).not.toBe(section);
    vi.runAllTimers();
    expect(document.activeElement).toBe(section);
  });
});
