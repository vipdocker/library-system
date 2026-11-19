// 公共空态（AC-UI-01）：Empty + 引导文案；管理员列表为空时可通过 action 提供"新建"按钮。
import type { ReactNode } from 'react';
import { Empty } from 'antd';

export interface EmptyStateProps {
  description?: ReactNode;
  action?: ReactNode;
}

export function EmptyState({ description = '暂无数据', action }: EmptyStateProps) {
  return <Empty description={description}>{action}</Empty>;
}