// 按角色的菜单定义（设计 10.1 节；AC-AUTH-09 / AC-UI-05）。
// READER：检索、我的借阅、我的资料、修改口令（D4①）。ADMIN：全部管理入口 + 修改口令。
import type { ComponentType } from 'react';
import {
  BarChartOutlined,
  BellOutlined,
  BookOutlined,
  DashboardOutlined,
  FileTextOutlined,
  KeyOutlined,
  ReadOutlined,
  SearchOutlined,
  SettingOutlined,
  SwapOutlined,
  TagsOutlined,
  TeamOutlined,
  UserOutlined,
  WarningOutlined,
} from '@ant-design/icons';
import type { Role } from '../api/types';

export interface MenuEntry {
  /** 路由路径，同时作为 Menu item key */
  key: string;
  label: string;
  icon: ComponentType;
}

const READER_MENU: MenuEntry[] = [
  { key: '/books', label: '图书检索', icon: SearchOutlined },
  { key: '/my/loans', label: '我的借阅', icon: ReadOutlined },
  { key: '/my/notifications', label: '我的提醒', icon: BellOutlined },
  { key: '/my/profile', label: '我的资料', icon: UserOutlined },
  { key: '/account/password', label: '修改口令', icon: KeyOutlined },
];

const ADMIN_MENU: MenuEntry[] = [
  { key: '/admin/dashboard', label: '仪表盘', icon: DashboardOutlined },
  { key: '/books', label: '图书检索', icon: SearchOutlined },
  { key: '/admin/books', label: '图书管理', icon: BookOutlined },
  { key: '/admin/categories', label: '分类管理', icon: TagsOutlined },
  { key: '/admin/readers', label: '读者管理', icon: TeamOutlined },
  { key: '/admin/circulation', label: '流通柜台', icon: SwapOutlined },
  { key: '/admin/loans', label: '借阅记录', icon: FileTextOutlined },
  { key: '/admin/reports/overdue', label: '逾期报表', icon: WarningOutlined },
  { key: '/admin/dunning', label: '催还记录', icon: FileTextOutlined },
  { key: '/admin/stats', label: '借阅统计', icon: BarChartOutlined },
  { key: '/admin/settings', label: '系统设置', icon: SettingOutlined },
  { key: '/account/password', label: '修改口令', icon: KeyOutlined },
];

export function menuForRole(role: Role): MenuEntry[] {
  return role === 'ADMIN' ? ADMIN_MENU : READER_MENU;
}

/** 按最长前缀匹配当前路径对应的菜单项（如 /admin/books/3/edit → /admin/books）。 */
export function selectedMenuKey(pathname: string, entries: MenuEntry[]): string | undefined {
  let best: string | undefined;
  for (const entry of entries) {
    if (pathname === entry.key || pathname.startsWith(`${entry.key}/`)) {
      if (!best || entry.key.length > best.length) best = entry.key;
    }
  }
  return best;
}