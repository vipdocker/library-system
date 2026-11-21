// 逾期报表（设计 8.4 节 GET /api/reports/overdue）：只列未还且已过应还日的借阅，
// 默认按逾期天数降序；排序白名单 overdueDays、dueDate；分页遵循 8.1 节（page 从 0 起）。
// 页面状态遵循 10.2 节：首次加载 Spin；翻页/排序保留旧数据并显示表格 loading；
// 空态 Empty；错误 Alert + 重试（503 显示“系统繁忙”，403 显示“无权限执行该操作”）。
import { useState } from 'react';
import { Button, Space, Table, Tag } from 'antd';
import type { TableProps } from 'antd';
import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query';
import type { OverdueItem } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { tablePagination } from '../../../components/pagination';
import { fetchOverdueReport } from '../api';
import { DunningRegistrationModal } from '../../dunning/components/DunningRegistrationModal';
import { dunningKeys, dunningQueryOptions } from '../../dunning/api';
import '../stats.css';

type SortField = 'overdueDays' | 'dueDate';
interface SortState {
  field: SortField;
  order: 'asc' | 'desc';
}

const DEFAULT_SORT: SortState = { field: 'overdueDays', order: 'desc' };
const SORT_FIELDS: SortField[] = ['overdueDays', 'dueDate'];

function isSortField(value: unknown): value is SortField {
  return SORT_FIELDS.includes(value as SortField);
}

export default function OverdueReportPage() {
  const client = useQueryClient();
  const [selected, setSelected] = useState<OverdueItem | null>(null);
  const [pagination, setPagination] = useState({ page: 0, size: 20 });
  const [sort, setSort] = useState<SortState>(DEFAULT_SORT);
  const sortParam = `${sort.field},${sort.order}`;

  const reportQuery = useQuery({
    ...dunningQueryOptions,
    queryKey: ['reports', 'overdue', pagination.page, pagination.size, sortParam],
    queryFn: ({ signal }) =>
      fetchOverdueReport({ page: pagination.page, size: pagination.size, sort: sortParam }, signal),
    placeholderData: keepPreviousData,
  });

  const data = reportQuery.data;
  const refresh = () => {
    setSelected(null);
    void client.invalidateQueries({ queryKey: dunningKeys.all });
    void reportQuery.refetch();
  };

  const columns: TableProps<OverdueItem>['columns'] = [
    {
      title: '读者',
      key: 'reader',
      render: (_value, item) => (
        <div>
          <div>{item.reader.name}</div>
          <div className="overdue-cell-sub overdue-mono">{item.reader.cardNo}</div>
        </div>
      ),
    },
    {
      title: '联系电话',
      key: 'phone',
      render: (_value, item) => item.reader.phone ?? '—',
    },
    {
      title: '图书',
      key: 'book',
      render: (_value, item) => (
        <div>
          <div>{item.book.title}</div>
          <div className="overdue-cell-sub overdue-mono">{item.book.isbn}</div>
        </div>
      ),
    },
    {
      title: '副本条码',
      dataIndex: 'copyBarcode',
      className: 'overdue-mono',
    },
    { title: '借出日', dataIndex: 'borrowDate' },
    {
      title: '应还日',
      dataIndex: 'dueDate',
      sorter: true,
      sortDirections: ['descend', 'ascend'],
      sortOrder: sort.field === 'dueDate' ? (sort.order === 'desc' ? 'descend' : 'ascend') : null,
    },
    {
      title: '逾期天数',
      dataIndex: 'overdueDays',
      align: 'right',
      sorter: true,
      sortDirections: ['descend', 'ascend'],
      sortOrder: sort.field === 'overdueDays' ? (sort.order === 'desc' ? 'descend' : 'ascend') : null,
      render: (days: number) => (
        <Tag color="error" className="overdue-days-tag">
          {days} 天
        </Tag>
      ),
    },
    {
      title: '操作', key: 'dunning',
      render: (_value, item) => <Button size="small" disabled={reportQuery.isFetching || reportQuery.isError} onClick={() => setSelected(item)} aria-label="登记催还">登记催还</Button>,
    },
  ];

  // 排序变化时回到第一页；翻页/改页大小由 tablePagination 的 onChange 处理。
  const handleTableChange: TableProps<OverdueItem>['onChange'] = (_pag, _filters, sorter) => {
    const single = Array.isArray(sorter) ? sorter[0] : sorter;
    if (!single || !isSortField(single.field) || !single.order) return;
    const next: SortState = { field: single.field, order: single.order === 'ascend' ? 'asc' : 'desc' };
    if (next.field === sort.field && next.order === sort.order) return;
    setSort(next);
    setPagination((prev) => ({ page: 0, size: prev.size }));
  };

  return (
    <section className="stats-page" aria-label="逾期报表">
      <header className="stats-page-head">
        <div>
          <h3>逾期报表</h3>
          <div className="stats-page-sub">未还且已过应还日的借阅，默认按逾期天数降序</div>
        </div>
        <Space wrap>
          {data && data.totalItems > 0 && <Tag color="error" className="overdue-days-tag">共 {data.totalItems} 条逾期</Tag>}
          <Button onClick={refresh} disabled={reportQuery.isFetching} aria-label="刷新逾期报表">刷新逾期报表</Button>
        </Space>
      </header>

      {reportQuery.isError && (
        <PageError error={reportQuery.error} onRetry={() => reportQuery.refetch()} />
      )}

      {reportQuery.isPending ? (
        <div className="stats-section">
          <PageLoading tip="报表加载中…" />
        </div>
      ) : reportQuery.isError && !data ? null : (
        <div className="stats-section">
          <Table<OverdueItem>
            rowKey="loanId"
            columns={columns}
            dataSource={data?.items ?? []}
            loading={reportQuery.isFetching}
            onChange={handleTableChange}
            pagination={tablePagination(data, (pageIndex, pageSize) =>
              setPagination({ page: pageIndex, size: pageSize }),
            )}
            locale={{
              emptyText: (
                <EmptyState description="当前没有逾期未还的借阅，所有借阅都在应还日期内" />
              ),
            }}
            scroll={{ x: 960 }}
          />
        </div>
      )}
      {selected && <DunningRegistrationModal item={selected} onClose={() => setSelected(null)} onRefresh={refresh} />}
    </section>
  );
}