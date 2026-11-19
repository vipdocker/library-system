// 可借数量展示（AC-BOOK-03 UI）：可借 / 馆藏，可借为 0 时明确提示"无可借"。
import { Space, Tag, Typography } from 'antd';

export interface AvailabilityBadgeProps {
  availableCopies: number;
  totalCopies: number;
}

export function AvailabilityBadge({ availableCopies, totalCopies }: AvailabilityBadgeProps) {
  return (
    <Space size={6}>
      <Tag color={availableCopies > 0 ? 'success' : 'default'} style={{ marginInlineEnd: 0 }}>
        {availableCopies > 0 ? '可借' : '无可借'}
      </Tag>
      <Typography.Text type="secondary" style={{ fontVariantNumeric: 'tabular-nums' }}>
        {availableCopies} / {totalCopies}
      </Typography.Text>
    </Space>
  );
}