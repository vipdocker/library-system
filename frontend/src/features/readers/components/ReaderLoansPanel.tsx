// 读者详情页的借阅列表（T09 交接包：数据来自 GET /api/loans?readerId）。
// 实现第 10.2 节全部数据页状态：加载 / 空态 / 错误（重试）/ 有数据；翻页保留旧数据。
import { useState } from 'react';
import { Select, Space, Table, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import type { Loan, LoanStatusFilter } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { tablePagination } from '../../../components/pagination';
import { LOAN_SORT_FIELDS, listReaderLoans, readersKeys, type ReaderLoanQuery } from '../api';

const STATUS_OPTIONS: { value: LoanStatusFilter | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '全部状态' },
  { value: 'CURRENT', label: '当前在借' },
  { value: 'ACTIVE', label: '在借（未逾期）' },
  { value: 'OVERDUE', label: '逾期' },
  { value: 'RETURNED', label: '已还' },
];

export function LoanStatusTag({ loan }: { loan: Loan }) {
  if (loan.status === 'OVERDUE') {
    return <Tag color="error">{`逾期 ${loan.overdueDays} 天`}</Tag>;
  }
  if (loan.status === 'ACTIVE') {
    return <Tag color="success">在借</Tag>;
  }
  return <Tag>已还</Tag>;
}

export function ReaderLoansPanel({ readerId }: { readerId: number }) {
  const [query, setQuery] = useState<ReaderLoanQuery>({ page: 0, size: 20 });
  const [statusDraft, setStatusDraft] = useState<LoanStatusFilter | 'ALL'>('ALL');

  const { data, isPending, isError, error, isFetching, refetch } = useQuery({
    queryKey: readersKeys.loans(readerId, query),
    queryFn: () => listReaderLoans(readerId, query),
    placeholderData: keepPreviousData,
  });

  const hasFilter = query.status !== undefined;
  const columns: TableProps<Loan>['columns'] = [
    {
      title: '书名',
      key: 'title',
      render: (_, loan) => (
        <Space orientation="vertical" size={0}>
          <Typography.Text>{loan.book.title}</Typography.Text>
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            {loan.book.author}
          </Typography.Text>
        </Space>
      ),
    },
    { title: '条码', key: 'barcode', render: (_, loan) => loan.copy.barcode },
    { title: '借阅日', dataIndex: 'borrowDate', key: 'borrowDate', sorter: true },
    { title: '应还日', dataIndex: 'dueDate', key: 'dueDate', sorter: true },
    {
      title: '归还日',
      key: 'returnDate',
      render: (_, loan) => loan.returnDate ?? '—',
    },
    { title: '状态', key: 'status', render: (_, loan) => <LoanStatusTag loan={loan} /> },
    {
      title: '续借次数',
      key: 'renewCount',
      render: (_, loan) => `${loan.renewCount}/${loan.maxRenewals}`,
    },
  ];

  const handleTableChange: TableProps<Loan>['onChange'] = (_pagination, _filters, sorter, extra) => {
    if (extra.action !== 'sort') return;
    const single = Array.isArray(sorter) ? sorter[0] : sorter;
    const field = String(single?.columnKey ?? '');
    const sort =
      single?.order && (LOAN_SORT_FIELDS as readonly string[]).includes(field)
        ? `${field},${single.order === 'ascend' ? 'asc' : 'desc'}`
        : undefined;
    setQuery((prev) => ({ ...prev, sort, page: 0 }));
  };

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Space>
        <span>状态过滤：</span>
        <div data-testid="loan-status-filter">
          <Select
            style={{ width: 180 }}
            value={statusDraft}
            options={STATUS_OPTIONS}
            onChange={(value) => {
              setStatusDraft(value);
              setQuery((prev) => ({
                ...prev,
                status: value === 'ALL' ? undefined : value,
                page: 0,
              }));
            }}
          />
        </div>
      </Space>
      {isPending ? (
        <PageLoading tip="借阅记录加载中…" />
      ) : isError ? (
        <PageError error={error} onRetry={() => void refetch()} />
      ) : (
        <Table<Loan>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={isFetching}
          onChange={handleTableChange}
          pagination={tablePagination(data, (page, size) =>
            setQuery((prev) => ({ ...prev, page, size })),
          )}
          locale={{
            emptyText: (
              <EmptyState
                description={hasFilter ? '没有找到匹配的借阅记录' : '该读者暂无借阅记录'}
              />
            ),
          }}
        />
      )}
    </Space>
  );
}