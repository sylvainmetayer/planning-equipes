import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { beforeEach, describe, expect, it } from 'vitest';
import { App } from './app';
import { APP_CONFIG, DEFAULT_APP_CONFIG } from './core/app-config';

describe('App', () => {
  beforeEach(() => TestBed.resetTestingModule());

  function render(demoInstance: boolean): HTMLElement {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: APP_CONFIG, useValue: { ...DEFAULT_APP_CONFIG, demoInstance } },
      ],
    });
    const fixture = TestBed.createComponent(App);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  it('shows the demo banner above every page of a demo instance', () => {
    expect(render(true).querySelector('.demo-banner')?.textContent).toContain('remise à zéro');
  });

  it('shows nothing on any other instance', () => {
    expect(render(false).querySelector('.demo-banner')).toBeNull();
  });
});
