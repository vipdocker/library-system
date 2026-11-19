// 管理员读者列表（AC-RDR-01/02/03/04、AC-UI-01/02）：检索、新建、停用/启用、删除。
// 页面状态按第 10.2 节：首载 Spin；翻页/筛选保留旧数据 + 表格 loading；错误 Alert + 重试；
// 空态区分"无读者（引导新建）"与"检索无结果"；删除/停用 Popconfirm 二次确认。
import { useState } from 'react';
import { App, Button, Card, Input, Popconfirm, Select, Space, Table, Typography } from 'antd';
import type { TableProps } from 'antd';
import { PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router';
import dayjs from 'dayjs';
import type { Reader, ReaderStatus } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { tablePagination } from '../../../components/pagination';
import {
  READER_SORT_FIELDS,
  deleteReader,
  listReaders,
  readersKeys,
  type ReaderQuery,
} from '../api';
import { apiErrorMessage } from '../formErrors';
import { ReaderCreateModal } from '../components/ReaderCreateModal';
import { ReaderStatusAction } from '../components/ReaderStatusAction';
import { ReaderStatusTag } from '../components/ReaderStatusTag';

const STATUS_OPTIONS: { value: ReaderStatus | 'ALL'; label: string }[] = [
  { value: 'ALL', label: '全部状态' },
  { value: 'ACTIVE', label: '启用' },
  { value: 'DISABLED', label: '停用' },
];

export default function AdminReaderListPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();

  const [query, setQuery] = useState<ReaderQuery>({ q: '', page: 0, size: 20 });
  const [qDraft, setQDraft] = useState('');
  const [statusDraft, setStatusDraft] = useState<ReaderStatus | 'ALL'>('ALL');
  const [createOpen, setCreateOpen] = useState(false);

  const { data, isPending, isError, error, isFetching, refetch } = useQuery({
    queryKey: readersKeys.list(query),
    queryFn: () => listReaders(query),
    placeholderData: keepPreviousData,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteReader(id),
    onSuccess: () => {
      message.success('读者已删除，账号一并删除');
      void queryClient.invalidateQueries({ queryKey: readersKeys.all });
    },
    onError: (err) => message.error(apiErrorMessage(err)),
  });

  const applySearch = () => {
    setQuery((prev) => ({
      ...prev,
      q: qDraft.trim(),
      status: statusDraft === 'ALL' ? undefined : statusDraft,
      page: 0,
    }));
  };

  const resetSearch = () => {
    setQDraft('');
    setStatusDraft('ALL');
    setQuery((prev) => ({ ...prev, q: '', status: undefined, page: 0 }));
  };

  const hasFilter = query.q !== '' || query.status !== undefined;
  const isEmpty = (data?.totalItems ?? 0) === 0;

  const columns: TableProps<Reader>['columns'] = [
    { title: '卡号', dataIndex: 'cardNo', key: 'cardNo', sorter: true },
    {
      title: '姓名',
      dataIndex: 'name',
      key: 'name',
      sorter: true,
      render: (name: string, reader) => <Link to={`/admin/readers/${reader.id}`}>{name}</Link>,
    },
    { title: '用户名', dataIndex: 'username', key: 'username' },
    { title: '电话', key: 'phone', render: (_, reader) => reader.phone ?? '—' },
    { title: '邮箱', key: 'email', render: (_, reader) => reader.email ?? '—' },
    {
      title: '状态',
      key: 'status',
      render: (_, reader) => <ReaderStatusTag status={reader.status} />,
    },
    { title: '在借', dataIndex: 'activeLoanCount', key: 'activeLoanCount' },
    {
      title: '逾期',
      key: 'overdueLoanCount',
      render: (_, reader) =>
        reader.overdueLoanCount > 0 ? (
          <Typography.Text type="danger">{reader.overdueLoanCount}</Typography.Text>
        ) : (
          reader.overdueLoanCount
        ),
    },
    {
      title: '创建时间',
      key: 'createdAt',
      sorter: true,
      render: (_, reader) => dayjs(reader.createdAt).format('YYYY-MM-DD'),
    },
    {
      title: '操作',
      key: 'actions',
      render: (_, reader) => (
        <Space size="small">
          <Link to={`/admin/readers/${reader.id}`}>详情</Link>
          <ReaderStatusAction reader={reader} />
          <Popconfirm
            title="删除读者"
            description={`删除后账号一并删除，且不可恢复。确认删除「${reader.name}」？`}
            okText="确认"
            cancelText="取消"
            okButtonProps={{ danger: true, loading: deleteMutation.isPending }}
            onConfirm={() => deleteMutation.mutate(reader.id)}
          >
            <Button
              size="small"
              danger
              loading={deleteMutation.isPending && deleteMutation.variables === reader.id}
            >
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const handleTableChange: TableProps<Reader>['onChange'] = (
    _pagination,
    _filters,
    sorter,
    extra,
  ) => {
    if (extra.action !== 'sort') return;
    const single = Array.isArray(sorter) ? sorter[0] : sorter;
    const field = String(single?.columnKey ?? '');
    const sort =
      single?.order && (READER_SORT_FIELDS as readonly string[]).includes(field)
        ? `${field},${single.order === 'ascend' ? 'asc' : 'desc'}`
        : undefined;
    setQuery((prev) => ({ ...prev, sort, page: 0 }));
  };

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
        <Typography.Title level={3} style={{ margin: 0 }}>
          读者管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setCreateOpen(true)}>
          新建读者
        </Button>
      </Space>

      <Card size="small">
        <Space wrap>
          <Input
            style={{ width: 320 }}
            placeholder="姓名子串 / 卡号或用户名精确匹配"
            value={qDraft}
            allowClear
            onChange={(e) => setQDraft(e.target.value)}
            onPressEnter={applySearch}
            prefix={<SearchOutlined />}
            aria-label="读者检索"
          />
          <div data-testid="reader-status-filter">
            <Select
              style={{ width: 140 }}
              value={statusDraft}
              options={STATUS_OPTIONS}
              onChange={setStatusDraft}
            />
          </div>
          <Button type="primary" onClick={applySearch}>
            查询
          </Button>
          <Button onClick={resetSearch}>重置</Button>
        </Space>
      </Card>

      {isPending ? (
        <PageLoading />
      ) : isError ? (
        <PageError error={error} onRetry={() => void refetch()} />
      ) : (
        <Table<Reader>
          rowKey="id"
          columns={columns}
          dataSource={data?.items ?? []}
          loading={isFetching}
          onChange={handleTableChange}
          pagination={tablePagination(data, (page, size) =>
            setQuery((prev) => ({ ...prev, page, size })),
          )}
          locale={{
            emptyText: isEmpty ? (
              <EmptyState
                description={hasFilter ? '没有找到匹配的读者' : '暂无读者'}
                action={
                  hasFilter ? (
                    <Button onClick={resetSearch}>清除筛选</Button>
                  ) : (
                    <Button type="primary" onClick={() => setCreateOpen(true)}>
                      新建读者
                    </Button>
                  )
                }
              />
            ) : undefined,
          }}
        />
      )}

      <ReaderCreateModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={() => {
          setCreateOpen(false);
          void queryClient.invalidateQueries({ queryKey: readersKeys.all });
        }}
      />
    </Space>
  );
}