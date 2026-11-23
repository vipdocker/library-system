// AC-AUTH-09 / AC-UI-05：菜单按角色隔离；D4① 修改口令入口两个角色都有。
import { describe, expect, it } from 'vitest';
import { menuForRole, selectedMenuKey } from '../menu';

describe('AC-AUTH-09 / AC-UI-05 角色菜单', () => {
  it('AC-UI-05 v1.1：保留读者菜单并增加我的提醒', () => {
    const keys = menuForRole('READER').map((entry) => entry.key);
    expect(keys).toEqual(['/books', '/my/loans', '/my/notifications', '/my/profile', '/account/password']);
  });

  it('READER 菜单不含任何 /admin/* 入口', () => {
    expect(menuForRole('READER').some((entry) => entry.key.startsWith('/admin'))).toBe(false);
  });

  it('ADMIN 菜单包含全部管理入口与修改口令（D4①）', () => {
    const keys = menuForRole('ADMIN').map((entry) => entry.key);
    for (const required of [
      '/admin/dashboard',
      '/admin/books',
      '/admin/categories',
      '/admin/readers',
      '/admin/circulation',
      '/admin/loans',
      '/admin/reports/overdue',
      '/admin/dunning',
      '/admin/stats',
      // showapi-design-v1 §7：系统设置单菜单入口改为容器路径，代理/ShowAPI 为页签（FE-01 披露修订）。
      '/admin/settings',
      '/account/password',
    ]) {
      expect(keys, required).toContain(required);
    }
  });

  it('每个菜单项都有标签与图标', () => {
    for (const role of ['ADMIN', 'READER'] as const) {
      for (const entry of menuForRole(role)) {
        expect(entry.label).toBeTruthy();
        expect(entry.icon).toBeTruthy();
      }
    }
  });
});

describe('selectedMenuKey 最长前缀匹配', () => {
  const entries = menuForRole('ADMIN');

  it('子路径归属到父菜单', () => {
    expect(selectedMenuKey('/admin/books/3/edit', entries)).toBe('/admin/books');
    expect(selectedMenuKey('/admin/books/new', entries)).toBe('/admin/books');
    expect(selectedMenuKey('/admin/readers/9', entries)).toBe('/admin/readers');
    expect(selectedMenuKey('/books/5', entries)).toBe('/books');
    expect(selectedMenuKey('/admin/settings/proxy', entries)).toBe('/admin/settings');
    expect(selectedMenuKey('/admin/settings/showapi', entries)).toBe('/admin/settings');
  });

  it('精确匹配与未匹配', () => {
    expect(selectedMenuKey('/admin/dashboard', entries)).toBe('/admin/dashboard');
    expect(selectedMenuKey('/403', entries)).toBeUndefined();
  });
});