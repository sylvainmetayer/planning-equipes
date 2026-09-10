import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationService } from './notification.service';

function configure(): {
  service: NotificationService;
  snackBar: { open: ReturnType<typeof vi.fn> };
} {
  const snackBar = { open: vi.fn() };
  TestBed.configureTestingModule({
    providers: [
      provideZonelessChangeDetection(),
      NotificationService,
      { provide: MatSnackBar, useValue: snackBar },
    ],
  });
  return { service: TestBed.inject(NotificationService), snackBar };
}

describe('NotificationService', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('starts with an empty, persisted log', () => {
    const { service } = configure();
    expect(service.notifications()).toEqual([]);
    expect(service.unreadCount()).toBe(0);
  });

  it('notify() shows a snack bar and logs the notification, newest first', () => {
    const { service, snackBar } = configure();
    service.notify({ title: 'First' });
    service.notify({ title: 'Second', message: 'details' });
    expect(snackBar.open).toHaveBeenCalledTimes(2);
    const [first, second] = service.notifications();
    expect(first.title).toBe('Second');
    expect(second.title).toBe('First');
  });

  it.each([
    ['info' as const, 'info' as const],
    ['success' as const, 'info' as const],
    ['warning' as const, 'warning' as const],
    ['error' as const, 'alert' as const],
  ])('maps toast variant %s to severity %s', (variant, severity) => {
    const { service } = configure();
    service.notify({ title: 'x', variant });
    expect(service.notifications()[0].severity).toBe(severity);
  });

  /**
   * The log is written to `localStorage` and outlives the logout, so a sentence
   * that may be shown is not always a sentence that may be kept — a warning
   * naming a minor, typically (docs/rgpd.md §7).
   */
  it('keeps messageJournal in the log while the snack bar shows message', () => {
    const { service, snackBar } = configure();

    service.notify({
      title: 'Saved',
      message: 'A1 is a minor · off day 2027-08-15',
      messageJournal: 'off day 2027-08-15',
    });

    expect(snackBar.open).toHaveBeenCalledWith(
      expect.stringContaining('A1 is a minor'),
      expect.anything(),
      expect.anything(),
    );
    expect(service.notifications()[0].message).toBe('off day 2027-08-15');

    // And nothing of it survives into the next session either.
    TestBed.resetTestingModule();
    const { service: relu } = configure();
    expect(relu.notifications()[0].message).toBe('off day 2027-08-15');
  });

  it('silent notify() logs without showing a snack bar', () => {
    const { service, snackBar } = configure();
    service.notify({ title: 'Quiet', silent: true });
    expect(snackBar.open).not.toHaveBeenCalled();
    expect(service.notifications()).toHaveLength(1);
  });

  it('push() logs an entry directly, unread by default', () => {
    const { service } = configure();
    service.push('alert', 'Broken constraint', 'detail');
    expect(service.unreadCount()).toBe(1);
    expect(service.notifications()[0]).toMatchObject({
      severity: 'alert',
      title: 'Broken constraint',
      read: false,
    });
  });

  it('markRead() clears the unread flag for one entry only', () => {
    const { service } = configure();
    service.push('info', 'A');
    service.push('warning', 'B');
    const [newest] = service.notifications();
    service.markRead(newest.id);
    expect(service.unreadCount()).toBe(1);
    expect(service.notifications().find((n) => n.id === newest.id)?.read).toBe(true);
  });

  it('markAllRead() clears every unread flag', () => {
    const { service } = configure();
    service.push('info', 'A');
    service.push('warning', 'B');
    service.markAllRead();
    expect(service.unreadCount()).toBe(0);
  });

  it('clear() empties the log', () => {
    const { service } = configure();
    service.push('info', 'A');
    service.clear();
    expect(service.notifications()).toEqual([]);
  });

  it('persists across service instances via localStorage', () => {
    const { service: first } = configure();
    first.push('warning', 'Persisted entry');

    TestBed.resetTestingModule();
    const { service: second } = configure();
    expect(second.notifications()).toHaveLength(1);
    expect(second.notifications()[0].title).toBe('Persisted entry');
  });
});
