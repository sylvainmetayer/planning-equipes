import { provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { ApiService } from '../api.service';
import { RhApi } from './rh-api';

describe('RhApi', () => {
  it('names the edition in the path of every call', async () => {
    const get = vi.fn().mockResolvedValue([]);
    const put = vi.fn().mockResolvedValue({});
    const downloadGet = vi.fn().mockResolvedValue('ok');
    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        { provide: ApiService, useValue: { get, put, downloadGet } },
      ],
    });
    const rh = TestBed.inject(RhApi);

    await rh.editions();
    await rh.rapport('E 1');
    await rh.pdf('E1');
    await rh.csv('E1');
    await rh.contacts('E1');
    await rh.saveContacts('E1', 'A 1', { urgenceNom: 'Camille' });

    expect(get).toHaveBeenCalledWith('/api/rh/editions');
    expect(get).toHaveBeenCalledWith('/api/rh/editions/E%201/conformite');
    expect(get).toHaveBeenCalledWith('/api/rh/editions/E1/contacts');
    expect(put).toHaveBeenCalledWith('/api/rh/editions/E1/contacts/A%201', {
      urgenceNom: 'Camille',
    });
    expect(downloadGet).toHaveBeenCalledWith(
      '/api/rh/editions/E1/conformite.pdf',
      'rapport-conformite.pdf',
      'application/pdf',
    );
    expect(downloadGet).toHaveBeenCalledWith(
      '/api/rh/editions/E1/conformite.csv',
      'rapport-conformite.csv',
      'text/csv',
    );
  });
});
