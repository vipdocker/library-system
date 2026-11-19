// 管理员借阅记录（AC-LOAN-04/05 UI、AC-LOAN-08、AC-UI-01，设计 10.2 节）：
// - 按读者（远程搜索下拉 → readerId）、状态（ACTIVE/OVERDUE/RETURNED/CURRENT）、副本条码、书名过滤；
// - 分页 + 排序（白名单 borrowDate、dueDate，默认 borrowDate,desc）；
// - 在借行可直接还书（Popconfirm 二次确认）或续借（renewable=false 时禁用）；
// - 首次加载 Spin；翻页/筛选保留旧数据、表格 loading；错误 Alert 中文文案 + 重试；空态提供去柜台借书入口。
import { useDeferredValue, useState } from 'react';
import { useMutation, useQuery, useQueryClient, keepPreviousData } from '@tanstack/react-query';
import {
  App as AntdApp,
  Button,
  Input,
  Popconfirm,
  Select,
  Space,
  Table,
  Typography,
  type TableProps,
} from 'antd';
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router';
import type { Loan, LoanStatusFilter } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { tablePagination } from '../../../components/pagination';
import {
  DEFAULT_LOAN_QUERY,
  DEFAULT_LOAN_SORT,
  circulationKeys,
  listLoans,
  renewLoan,
  returnLoan,
  searchReaders,
  type LoanQuery,
} from '../api';
import { loanErrorText } from '../loanErrorText';
import { catalogKeys } from '../../catalog/api';
import { LoanStatusTag } from '../components/LoanStatusTag';
import '../circulation.css';

const STATUS_OPTIONS: { value: LoanStatusFilter | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '全部状态' },
  { value: 'CURRENT', label: '在借（含逾期）' },
  { value: 'ACTIVE', label: '在借' },
  { value: 'OVERDUE', label: '逾期' },
  { value: 'RETURNED', label: '已归还' },
];

type SortOrder = 'ascend' | 'descend' | null;

function sortOrderOf(sort: string, field: string): SortOrder {
  const [sortField, dir] = sort.split(',');
  if (sortField !== field) return null;
  return dir === 'desc' ? 'descend' : 'ascend';
}

interface ReaderOption {
  value: number;
  label: string;
}

export default function AdminLoanListPage() {
  const { message } = AntdApp.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  // 草稿过滤条件（点「查询」或回车后生效）与已生效查询
  const [draftReader, setDraftReader] = useState<ReaderOption | null>(null);
  const [readerSearch, setReaderSearch] = useState('');
  const [draftStatus, setDraftStatus] = useState<LoanStatusFilter | 'ALL'>('ALL');
  const [draftBarcode, setDraftBarcode] = useState('');
  const [draftQ, setDraftQ] = useState('');
  const [applied, setApplied] = useState<LoanQuery>(DEFAULT_LOAN_QUERY);

  const deferredSearch = useDeferredValue(readerSearch);
  const readerOptionsQuery = useQuery({
    queryKey: circulationKeys.readerSearch(deferredSearch.trim()),
    queryFn: () => searchReaders(deferredSearch),
    enabled: deferredSearch.trim().length > 0,
    placeholderData: keepPreviousData,
  });

  const loansQuery = useQuery({
    queryKey: circulationKeys.loans(applied),
    queryFn: () => listLoans(applied),
    placeholderData: keepPreviousData,
  });

  const invalidateLoans = () =>
    queryClient.invalidateQueries({ queryKey: circulationKeys.all });

  const returnMutation = useMutation({
    mutationFn: (loanId: number) => returnLoan(loanId),
    onSuccess: (loan) => {
      message.success(
        loan.overdueDays > 0
          ? `《${loan.book.title}》已归还（逾期 ${loan.overdueDays} 天）`
          : `《${loan.book.title}》已按期归还`,
      );
      void invalidateLoans();
      // I-05 / AC-BOOK-03：归还后可借数量变化，失效检索结果与图书详情缓存
      void queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
    onError: (error) => message.error(loanErrorText(error)),
  });

  const renewMutation = useMutation({
    mutationFn: (loanId: number) => renewLoan(loanId),
    onSuccess: (loan) => {
      message.success(`续借成功：《${loan.book.title}》应还日顺延至 ${loan.dueDate}`);
      void invalidateLoans();
    },
    onError: (error) => {
      message.error(loanErrorText(error));
      void invalidateLoans();
    },
  });

  const applyFilters = () => {
    setApplied({
      readerId: draftReader?.value,
      status: draftStatus === 'ALL' ? undefined : draftStatus,
      copyBarcode: draftBarcode.trim(),
      q: draftQ.trim(),
      page: 0,
      size: applied.size,
      sort: applied.sort,
    });
  };

  const resetFilters = () => {
    setDraftReader(null);
    setReaderSearch('');
    setDraftStatus('ALL');
    setDraftBarcode('');
    setDraftQ('');
    setApplied({ ...DEFAULT_LOAN_QUERY, size: applied.size });
  };

  // 远程搜索选项 + 已选中项（清空搜索词后仍保留选中读者的标签）
  const searchedOptions: ReaderOption[] = (readerOptionsQuery.data?.items ?? []).map((reader) => ({
    value: reader.id,
    label: `${reader.name}（${reader.cardNo}）`,
  }));
  const readerOptions =
    draftReader && !searchedOptions.some((option) => option.value === draftReader.value)
      ? [draftReader, ...searchedOptions]
      : searchedOptions;

  const columns: TableProps<Loan>['columns'] = [
    {
      title: '读者',
      key: 'reader',
      render: (_, loan) => (
        <div className="circ-book-cell">
          <span>{loan.reader.name}</span>
          <span className="circ-book-sub circ-barcode">{loan.reader.cardNo}</span>
        </div>
      ),
    },
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
    {
      title: '借阅日',
      dataIndex: 'borrowDate',
      key: 'borrowDate',
      sorter: true,
      sortOrder: sortOrderOf(applied.sort, 'borrowDate'),
    },
    {
      title: '应还日',
      dataIndex: 'dueDate',
      key: 'dueDate',
      sorter: true,
      sortOrder: sortOrderOf(applied.sort, 'dueDate'),
      render: (value: string, loan) => (
        <span className={loan.status === 'OVERDUE' ? 'circ-due-overdue' : undefined}>{value}</span>
      ),
    },
    {
      title: '状态',
      key: 'status',
      render: (_, loan) => <LoanStatusTag loan={loan} />,
    },
    {
      title: '归还日',
      dataIndex: 'returnDate',
      key: 'returnDate',
      render: (value: string | null) => value ?? '—',
    },
    {
      title: '续借',
      key: 'renewCount',
      render: (_, loan) => `${loan.renewCount}/${loan.maxRenewals}`,
    },
    {
      title: '操作',
      key: 'action',
      render: (_, loan) => {
        if (loan.status === 'RETURNED') return null;
        return (
          <Space size={8}>
            <Popconfirm
              title="确认归还这本书？"
              okText="还书"
              cancelText="取消"
              onConfirm={() => returnMutation.mutate(loan.id)}
            >
              <Button
                size="small"
                loading={returnMutation.isPending && returnMutation.variables === loan.id}
                disabled={returnMutation.isPending}
              >
                还书
              </Button>
            </Popconfirm>
            <Button
              size="small"
              disabled={!loan.renewable || renewMutation.isPending}
              loading={renewMutation.isPending && renewMutation.variables === loan.id}
              onClick={() => renewMutation.mutate(loan.id)}
            >
              续借
            </Button>
          </Space>
        );
      },
    },
  ];

  const pagination = tablePagination(loansQuery.data, (pageIndex, pageSize) =>
    setApplied((prev) => ({ ...prev, page: pageIndex, size: pageSize })),
  );

  return (
    <section>
      <div className="circ-page-header">
        <Typography.Title level={3} style={{ margin: 0 }}>
          借阅记录
        </Typography.Title>
        <Typography.Text type="secondary">按读者、状态、条码、书名过滤</Typography.Text>
      </div>

      <div className="circ-filter-bar">
        <div className="circ-filter-field">
          <label htmlFor="circ-filter-reader">读者</label>
          <Select
            id="circ-filter-reader"
            style={{ width: 220 }}
            placeholder="输入姓名或卡号搜索"
            showSearch
            allowClear
            filterOption={false}
            searchValue={readerSearch}
            onSearch={setReaderSearch}
            value={draftReader?.value}
            onChange={(value?: number) => {
              const option = readerOptions.find((item) => item.value === value);
              setDraftReader(option ?? null);
            }}
            options={readerOptions}
            loading={readerOptionsQuery.isFetching}
            notFoundContent={readerSearch.trim() ? '没有找到匹配的读者' : '输入关键字搜索读者'}
          />
        </div>
        <div className="circ-filter-field">
          <label htmlFor="circ-filter-status">状态</label>
          <Select
            id="circ-filter-status"
            style={{ width: 160 }}
            value={draftStatus}
            onChange={setDraftStatus}
            options={STATUS_OPTIONS}
          />
        </div>
        <div className="circ-filter-field">
          <label htmlFor="circ-filter-barcode">副本条码</label>
          <Input
            id="circ-filter-barcode"
            style={{ width: 170 }}
            placeholder="如 C00000001"
            value={draftBarcode}
            onChange={(event) => setDraftBarcode(event.target.value)}
            onPressEnter={applyFilters}
            allowClear
          />
        </div>
        <div className="circ-filter-field">
          <label htmlFor="circ-filter-q">书名</label>
          <Input
            id="circ-filter-q"
            style={{ width: 180 }}
            placeholder="书名子串"
            value={draftQ}
            onChange={(event) => setDraftQ(event.target.value)}
            onPressEnter={applyFilters}
            allowClear
          />
        </div>
        <Space>
          <Button type="primary" icon={<SearchOutlined />} onClick={applyFilters}>
            查询
          </Button>
          <Button icon={<ReloadOutlined />} onClick={resetFilters}>
            重置
          </Button>
        </Space>
      </div>

      {loansQuery.isPending ? (
        <PageLoading tip="正在加载借阅记录…" />
      ) : loansQuery.isError ? (
        <PageError error={loansQuery.error} onRetry={() => void loansQuery.refetch()} />
      ) : loansQuery.data.items.length === 0 ? (
        <EmptyState
          description="没有符合条件的借阅记录"
          action={
            <Button type="primary" onClick={() => navigate('/admin/circulation')}>
              前往流通柜台借书
            </Button>
          }
        />
      ) : (
        <Table<Loan>
          rowKey="id"
          columns={columns}
          dataSource={loansQuery.data.items}
          pagination={pagination}
          loading={loansQuery.isFetching}
          rowClassName={(loan) => (loan.status === 'OVERDUE' ? 'circ-row-overdue' : '')}
          onChange={(_pagination, _filters, sorter, extra) => {
            if (extra.action !== 'sort') return;
            const single = Array.isArray(sorter) ? sorter[0] : sorter;
            const sort = single?.order
              ? `${String(single.field)},${single.order === 'ascend' ? 'asc' : 'desc'}`
              : DEFAULT_LOAN_SORT;
            setApplied((prev) => ({ ...prev, sort, page: 0 }));
          }}
          scroll={{ x: 960 }}
        />
      )}
    </section>
  );
}