import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { NavModeService } from './nav-mode.service';

const STORAGE_KEY = 'planning-equipes.nav.mode';

describe('NavModeService', () => {
  beforeEach(() => {
    localStorage.removeItem(STORAGE_KEY);
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
  });

  afterEach(() => {
    localStorage.removeItem(STORAGE_KEY);
  });

  it('starts simple for a browser that never chose', () => {
    expect(TestBed.inject(NavModeService).mode()).toBe('simple');
  });

  it('starts from what the previous visit wrote', () => {
    localStorage.setItem(STORAGE_KEY, 'avance');
    expect(TestBed.inject(NavModeService).mode()).toBe('avance');
  });

  it('toggles, and writes the choice for the next visit', () => {
    const service = TestBed.inject(NavModeService);

    expect(service.toggle()).toBe('avance');
    expect(service.mode()).toBe('avance');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('avance');

    expect(service.toggle()).toBe('simple');
    expect(localStorage.getItem(STORAGE_KEY)).toBe('simple');
  });
});
