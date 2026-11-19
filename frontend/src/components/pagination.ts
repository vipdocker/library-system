// 分页辅助：把后端 Page<T>（page 从 0 起）换算成 antd Table 的 pagination（current 从 1 起）。
import type { TablePaginationConfig } from 'antd';
import type { Page } from '../api/types';

export function tablePagination<T>(
  page: Page<T> | undefined,
  onChange: (pageIndex: number, pageSize: number) => void,
): TablePaginationConfig {
  return {
    current: (page?.page ?? 0) + 1,
    pageSize: page?.size ?? 20,
    total: page?.totalItems ?? 0,
    showSizeChanger: true,
    pageSizeOptions: [10, 20, 50, 100],
    showTotal: (total) => `共 ${total} 条`,
    onChange: (current, size) => onChange(current - 1, size),
  };
}