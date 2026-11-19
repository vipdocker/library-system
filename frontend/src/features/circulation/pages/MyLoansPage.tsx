// 我的借阅（读者，AC-LOAN-05/07 UI、AC-UI-01/05，设计 10.3 节）：
// - 「当前借阅」（status=CURRENT）与「历史」（status=RETURNED）两个 Tab，只含本人记录（GET /api/loans/me）；
// - 逾期行标红并用红色 Tag 显示逾期天数；
// - 续借按钮只在 D2 开关允许（renewal.ts，默认 false）且服务端 renewable=true 时显示；
// - 首次加载 Spin；翻页/切 Tab 保留旧数据、表格 loading；错误 Alert 中文文案 + 重试；空态给引导。
import { useState } from 'react';
import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import {
  Alert,
  App as AntdApp,
  Button,
  Table,
  Tabs,
  Typography,
  type TableProps,
} from 'antd';
import { useNavigate } from 'react-router';
import type { Loan, LoanStatusFilter } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { tablePagination } from '../../../components/pagination';
import { circulationKeys, listMyLoans, renewLoan } from '../api';
import { loanErrorText } from '../loanErrorText';
import { READER_SELF_RENEW_ENABLED } from '../renewal';
import { LoanStatusTag } from '../components/LoanStatusTag';
import '../circulation.css';

type TabKey = 'CURRENT' | 'RETURNED';

interface PageState {
  page: number;
  size: number;
}

export default function MyLoansPage() {
  const { message } = AntdApp.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [tab, setTab] = useState<TabKey>('CURRENT');
  const [paging, setPaging] = useState<Record<TabKey, PageState>>({
    CURRENT: { page: 0, size: 20 },
    RETURNED: { page: 0, size: 20 },
  });

  const status: LoanStatusFilter = tab;
  const { page, size } = paging[tab];

  const loansQuery = useQuery({
    queryKey: circulationKeys.myLoans({ status, page, size }),
    queryFn: () => listMyLoans({ status, page, size }),
    placeholderData: keepPreviousData,
  });

  const renewMutation = useMutation({
    mutationFn: (loanId: number) => renewLoan(loanId),
    onSuccess: (loan) => {
      message.success(`续借成功：《${loan.book.title}》应还日顺延至 ${loan.dueDate}`);
      void queryClient.invalidateQueries({ queryKey: circulationKeys.all });
    },
    onError: (error) => {
      message.error(loanErrorText(error));
      void queryClient.invalidateQueries({ queryKey: circulationKeys.all });
    },
  });

  const overdueCount =
    tab === 'CURRENT'
      ? (loansQuery.data?.items.filter((loan) => loan.status === 'OVERDUE').length ?? 0)
      : 0;

  const columns: TableProps<Loan>['columns'] = [
    {
      title: '书名',
      key: 'title',
      render: (_, loan) => (
        <div className="circ-book-cell">
          <span style={{ fontWeight: 600 }}>{loan.book.title}</span>
          <span className="circ-book-sub">{loan.book.author}</span>
        </div>
      ),
    },
    {
      title: '条码',
      key: 'barcode',
      render: (_, loan) => <span className="circ-barcode">{loan.copy.barcode}</span>,
    },
    { title: '借阅日', dataIndex: 'borrowDate', key: 'borrowDate' },
    {
      title: '应还日',
      dataIndex: 'dueDate',
      key: 'dueDate',
      render: (value: string, loan) => (
        <span className={loan.status === 'OVERDUE' ? 'circ-due-overdue' : undefined}>{value}</span>
      ),
    },
    {
      title: '状态',
      key: 'status',
      render: (_, loan) => <LoanStatusTag loan={loan} />,
    },
    ...(tab === 'RETURNED'
      ? [
          {
            title: '归还日',
            dataIndex: 'returnDate',
            key: 'returnDate',
            render: (value: string | null) => value ?? '—',
          } satisfies NonNullable<TableProps<Loan>['columns']>[number],
        ]
      : []),
    ...(tab === 'CURRENT' && READER_SELF_RENEW_ENABLED
      ? [
          {
            title: '操作',
            key: 'action',
            render: (_: unknown, loan: Loan) =>
              loan.renewable ? (
                <Button
                  size="small"
                  disabled={renewMutation.isPending}
                  loading={renewMutation.isPending && renewMutation.variables === loan.id}
                  onClick={() => renewMutation.mutate(loan.id)}
                >
                  续借
                </Button>
              ) : null,
          } satisfies NonNullable<TableProps<Loan>['columns']>[number],
        ]
      : []),
  ];

  const pagination = tablePagination(loansQuery.data, (pageIndex, pageSize) =>
    setPaging((prev) => ({ ...prev, [tab]: { page: pageIndex, size: pageSize } })),
  );

  const emptyState =
    tab === 'CURRENT' ? (
      <EmptyState
        description="您当前没有在借图书"
        action={
          <Button type="primary" onClick={() => navigate('/books')}>
            去借几本书
          </Button>
        }
      />
    ) : (
      <EmptyState description="暂无历史借阅记录" />
    );

  return (
    <section>
      <div className="circ-page-header">
        <Typography.Title level={3} style={{ margin: 0 }}>
          我的借阅
        </Typography.Title>
        <Typography.Text type="secondary">借期 30 天 · 应还日当天归还仍算按期</Typography.Text>
      </div>

      {overdueCount > 0 && (
        <Alert
          className="circ-overdue-alert"
          type="warning"
          showIcon
          title={`您有 ${overdueCount} 册图书已逾期`}
          description="逾期期间不能借书和续借，请尽快归还。"
        />
      )}

      <Tabs
        activeKey={tab}
        onChange={(key) => setTab(key as TabKey)}
        items={[
          { key: 'CURRENT', label: '当前借阅' },
          { key: 'RETURNED', label: '历史' },
        ]}
      />

      {loansQuery.isPending ? (
        <PageLoading tip="正在加载借阅记录…" />
      ) : loansQuery.isError ? (
        <PageError error={loansQuery.error} onRetry={() => void loansQuery.refetch()} />
      ) : loansQuery.data.items.length === 0 ? (
        emptyState
      ) : (
        <Table<Loan>
          rowKey="id"
          columns={columns}
          dataSource={loansQuery.data.items}
          pagination={pagination}
          loading={loansQuery.isFetching}
          rowClassName={(loan) => (loan.status === 'OVERDUE' ? 'circ-row-overdue' : '')}
          scroll={{ x: 720 }}
        />
      )}
    </section>
  );
}