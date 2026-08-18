import { provideZonelessChangeDetection } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { StatusMessage } from './status-message';

describe('StatusMessage', () => {
  let fixture: ComponentFixture<StatusMessage>;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideZonelessChangeDetection()] });
    fixture = TestBed.createComponent(StatusMessage);
  });

  function paragraph(): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector('.status-message');
  }

  it('renders nothing without a message', async () => {
    await fixture.whenStable();
    expect(paragraph()).toBeNull();
  });

  it('interrupts on an error, stays polite otherwise', async () => {
    fixture.componentRef.setInput('text', 'Import impossible');
    fixture.componentRef.setInput('tone', 'error');
    await fixture.whenStable();
    expect(paragraph()?.getAttribute('role')).toBe('alert');
    expect(paragraph()?.textContent).toContain('Import impossible');

    fixture.componentRef.setInput('tone', 'success');
    await fixture.whenStable();
    expect(paragraph()?.getAttribute('role')).toBe('status');
  });

  it('hides its icon from assistive technology, the text carries the meaning', async () => {
    fixture.componentRef.setInput('text', 'Enregistré');
    await fixture.whenStable();
    expect(paragraph()?.querySelector('mat-icon')?.getAttribute('aria-hidden')).toBe('true');
  });
});
