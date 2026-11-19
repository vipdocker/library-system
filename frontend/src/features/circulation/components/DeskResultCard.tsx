// 柜台结果区（设计 10.2「业务失败」/ 10.3「流通柜台」）：
// 成功显示借阅摘要（书名、读者、应还日；归还时显示逾期天数）；失败显示 errors.ts 的中文文案。
import { Alert, Space, Tag, Typography } from 'antd';
import type { Loan } from '../../../api/types';

export type DeskResult =
  | { ok: true; kind: 'borrow' | 'return' | 'renew'; loan: Loan }
  | { ok: false; text: string };

const SUCCESS_TITLES: Record<'borrow' | 'return' | 'renew', string> = {
  borrow: '借出成功',
  return: '归还成功',
  renew: '续借成功',
};

export function DeskResultCard({ result }: { result: DeskResult }) {
  if (!result.ok) {
    return (
      <Alert
        className="circ-result"
        type="error"
        showIcon
        title="操作失败"
        description={result.text}
      />
    );
  }

  const { loan, kind } = result;
  return (
    <Alert
      className="circ-result"
      type="success"
      showIcon
      title={SUCCESS_TITLES[kind]}
      description={
        <Space orientation="vertical" size={4}>
          <Typography.Text strong>
            《{loan.book.title}》 {loan.book.author}
          </Typography.Text>
          <Typography.Text type="secondary">
            读者：{loan.reader.name}（{loan.reader.cardNo}） · 条码 {loan.copy.barcode}
          </Typography.Text>
          <Space size={8} wrap>
            <span>借阅日 {loan.borrowDate}</span>
            <span>
              应还日 <Typography.Text strong>{loan.dueDate}</Typography.Text>
            </span>
            {kind === 'return' && (
              <>
                <span>归还日 {loan.returnDate ?? '—'}</span>
                {loan.overdueDays > 0 ? (
                  <Tag color="error">逾期 {loan.overdueDays} 天</Tag>
                ) : (
                  <Tag color="success">按期归还</Tag>
                )}
              </>
            )}
            {kind === 'renew' && (
              <Tag color="processing">
                已续借 {loan.renewCount}/{loan.maxRenewals} 次
              </Tag>
            )}
          </Space>
        </Space>
      }
    />
  );
}