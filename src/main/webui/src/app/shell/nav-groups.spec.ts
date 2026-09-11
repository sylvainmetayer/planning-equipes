import { describe, expect, it } from 'vitest';
import { NavGroup, visibleNavGroups } from './nav-groups';

const GROUPS: NavGroup[] = [
  {
    id: 'planning',
    title: 'Planning',
    links: [
      { path: '/', label: 'Solveur', icon: 'play_circle' },
      { path: '/instantanes', label: 'Instantanés', icon: 'history', avance: true },
    ],
  },
  {
    id: 'experts',
    title: 'Experts',
    links: [{ path: '/debug', label: 'Débogage', icon: 'bug_report', avance: true }],
  },
];

describe('visibleNavGroups', () => {
  it('lists everything in the advanced mode', () => {
    expect(visibleNavGroups(GROUPS, 'avance', '/')).toEqual(GROUPS);
  });

  it('drops the advanced entries in the simple mode, and a group left empty with them', () => {
    const visible = visibleNavGroups(GROUPS, 'simple', '/');

    expect(visible.map((group) => group.id)).toEqual(['planning']);
    expect(visible[0].links.map((link) => link.path)).toEqual(['/']);
  });

  it('keeps the advanced entry whose route is on screen', () => {
    const visible = visibleNavGroups(GROUPS, 'simple', '/debug');

    expect(visible.map((group) => group.id)).toEqual(['planning', 'experts']);
    expect(visible[0].links.map((link) => link.path)).toEqual(['/']);
  });

  it('never mutates its input', () => {
    const before = JSON.stringify(GROUPS);
    visibleNavGroups(GROUPS, 'simple', '/');
    expect(JSON.stringify(GROUPS)).toBe(before);
  });
});
