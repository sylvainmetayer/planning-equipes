import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from '../core/notification.service';
import { OutputPanel } from './output-panel';

describe('OutputPanel', () => {
  let fixture: ComponentFixture<OutputPanel>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: NotificationService, useValue: { notify: vi.fn() } },
      ],
    });
    fixture = TestBed.createComponent(OutputPanel);
  });

  it('renders nothing while the text is empty', async () => {
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.output-panel')).toBeNull();
  });

  it('renders the text inside a monospaced panel once set', async () => {
    fixture.componentRef.setInput('text', 'Score 0hard/0medium/0soft');
    await fixture.whenStable();

    const pre = fixture.nativeElement.querySelector('pre') as HTMLElement | null;
    expect(pre).not.toBeNull();
    expect(pre!.textContent).toContain('Score 0hard/0medium/0soft');
  });

  it('hides the panel again when the text is cleared', async () => {
    fixture.componentRef.setInput('text', 'something');
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.output-panel')).not.toBeNull();

    fixture.componentRef.setInput('text', '');
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.output-panel')).toBeNull();
  });

  it('announces its output instead of writing into silence', async () => {
    fixture.componentRef.setInput('text', '3 erreurs de validation');
    await fixture.whenStable();

    const pre = fixture.nativeElement.querySelector('pre') as HTMLElement;
    expect(pre.getAttribute('role')).toBe('log');
    expect(pre.getAttribute('aria-live')).toBe('polite');
  });
});
