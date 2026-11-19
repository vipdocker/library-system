// 公共加载态（AC-UI-01）：首次加载显示 Spin；翻页/筛选时请改用表格自带 loading 保留旧数据。
import { Spin } from 'antd';

export function PageLoading({ tip = '加载中…' }: { tip?: string }) {
  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: 64,
        minHeight: 160,
      }}
    >
      <Spin size="large" />
      <span style={{ color: 'rgba(34, 48, 42, 0.55)' }}>{tip}</span>
    </div>
  );
}