// 借阅状态 Tag（AC-LOAN-07）：逾期用红色 Tag 标出逾期天数；在借 / 已归还用语义色区分。
import { Tag } from 'antd';
import type { Loan } from '../../../api/types';

export function LoanStatusTag({ loan }: { loan: Loan }) {
  if (loan.status === 'OVERDUE') {
    return <Tag color="error">逾期 {loan.overdueDays} 天</Tag>;
  }
  if (loan.status === 'ACTIVE') {
    return <Tag color="success">在借</Tag>;
  }
  return <Tag>已归还</Tag>;
}