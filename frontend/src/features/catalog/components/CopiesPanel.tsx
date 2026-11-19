// 副本管理面板（AC-COPY-01/02 UI，嵌在图书编辑页内）。
// 追加 1..50 个副本；AVAILABLE ↔ UNAVAILABLE 切换（设为不可用需二次确认）；删除需二次确认。
// 在借（BORROWED）副本不能改状态或删除（服务端 409 COPY_ON_LOAN，UI 直接禁用并说明原因）。
import { useState } from 'react';
import {
  App,
  Button,
  InputNumber,
  Popconfirm,
  Space,
  Table,
  Tag,
  Tooltip,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import dayjs from 'dayjs';
import type { Copy, CopyStatus } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { addCopies, catalogKeys, deleteCopy, listCopies, updateCopyStatus } from '../api';
import { apiErrorMessage } from '../formErrors';

const STATUS_META: Record<CopyStatus, { label: string; color: string }> = {
  AVAILABLE: { label: '可借', color: 'success' },
  BORROWED: { label: '在借', color: 'processing' },
  UNAVAILABLE: { label: '不可用', color: 'warning' },
};

export function CopiesPanel({ bookId }: { bookId: number }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [count, setCount] = useState<number>(1);

  const copiesQuery = useQuery({
    queryKey: catalogKeys.copies(bookId),
    queryFn: () => listCopies(bookId),
  });

  const invalidate = () => queryClient.invalidateQueries({ queryKey: catalogKeys.all });

  const addMutation = useMutation({
    mutationFn: (n: number) => addCopies(bookId, { count: n }),
    onSuccess: (created) => {
      message.success(`已追加 ${created.length} 个副本`);
      invalidate();
    },
    onError: (error) => message.error(apiErrorMessage(error)),
  });

  const statusMutation = useMutation({
    mutationFn: ({ copyId, status }: { copyId: number; status: 'AVAILABLE' | 'UNAVAILABLE' }) =>
      updateCopyStatus(copyId, { status }),
    onSuccess: (copy) => {
      message.success(`副本 ${copy.barcode} 已设为${STATUS_META[copy.status].label}`);
      invalidate();
    },
    onError: (error) => message.error(apiErrorMessage(error)),
  });

  const deleteMutation = useMutation({
    mutationFn: (copyId: number) => deleteCopy(copyId),
    onSuccess: () => {
      message.success('副本已删除');
      invalidate();
    },
    onError: (error) => message.error(apiErrorMessage(error)),
  });

  const busy = addMutation.isPending || statusMutation.isPending || deleteMutation.isPending;

  const columns: TableProps<Copy>['columns'] = [
    {
      title: '条码',
      dataIndex: 'barcode',
      render: (barcode: string) => (
        <Typography.Text code copyable={{ text: barcode }}>
          {barcode}
        </Typography.Text>
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (status: CopyStatus) => (
        <Tag color={STATUS_META[status].color}>{STATUS_META[status].label}</Tag>
      ),
    },
    {
      title: '入库时间',
      dataIndex: 'createdAt',
      width: 170,
      render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm'),
    },
    {
      title: '操作',
      key: 'actions',
      width: 220,
      render: (_, copy) => {
        if (copy.status === 'BORROWED') {
          return (
            <Tooltip title="在借副本不能修改状态或删除，请先在流通柜台归还">
              <Typography.Text type="secondary">在借中，归还后可操作</Typography.Text>
            </Tooltip>
          );
        }
        const toUnavailable = copy.status === 'AVAILABLE';
        const statusButton = (
          <Button
            size="small"
            disabled={busy}
            loading={statusMutation.isPending && statusMutation.variables?.copyId === copy.id}
            onClick={
              toUnavailable
                ? undefined
                : () => statusMutation.mutate({ copyId: copy.id, status: 'AVAILABLE' })
            }
          >
            {toUnavailable ? '设为不可用' : '恢复可借'}
          </Button>
        );
        return (
          <Space size={8}>
            {toUnavailable ? (
              <Popconfirm
                title="设为不可用？"
                description="不可用的副本不能被借出。"
                okText="确认设为不可用"
                cancelText="取消"
                onConfirm={() =>
                  statusMutation.mutate({ copyId: copy.id, status: 'UNAVAILABLE' })
                }
              >
                {statusButton}
              </Popconfirm>
            ) : (
              statusButton
            )}
            <Popconfirm
              title="删除该副本？"
              description="删除后不可恢复；有借阅历史的副本无法删除。"
              okText="确认删除"
              okButtonProps={{ danger: true }}
              cancelText="取消"
              onConfirm={() => deleteMutation.mutate(copy.id)}
            >
              <Button size="small" danger icon={<DeleteOutlined />} disabled={busy}>
                删除
              </Button>
            </Popconfirm>
          </Space>
        );
      },
    },
  ];

  return (
    <section aria-label="副本管理">
      <Space
        style={{ width: '100%', justifyContent: 'space-between', marginBottom: 12 }}
        align="center"
        wrap
      >
        <Typography.Title level={5} style={{ margin: 0 }}>
          副本管理
          {copiesQuery.data && (
            <Typography.Text type="secondary" style={{ marginLeft: 8, fontSize: 13, fontWeight: 400 }}>
              共 {copiesQuery.data.length} 册，可借{' '}
              {copiesQuery.data.filter((c) => c.status === 'AVAILABLE').length} 册
            </Typography.Text>
          )}
        </Typography.Title>
        <Space size={8}>
          <InputNumber
            aria-label="追加数量"
            min={1}
            max={50}
            precision={0}
            value={count}
            onChange={(value) => setCount(value ?? 1)}
            style={{ width: 96 }}
            disabled={addMutation.isPending}
          />
          <Button
            type="primary"
            ghost
            icon={<PlusOutlined />}
            loading={addMutation.isPending}
            disabled={addMutation.isPending || count < 1 || count > 50}
            onClick={() => addMutation.mutate(count)}
          >
            追加副本
          </Button>
        </Space>
      </Space>
      {copiesQuery.isPending ? (
        <PageLoading tip="副本加载中…" />
      ) : copiesQuery.isError ? (
        <PageError error={copiesQuery.error} onRetry={() => copiesQuery.refetch()} />
      ) : (
        <Table<Copy>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={copiesQuery.data}
          loading={copiesQuery.isFetching}
          pagination={false}
          scroll={{ x: 640 }}
          locale={{
            emptyText: (
              <EmptyState description="暂无副本，请在右上方追加副本（1–50 册）" />
            ),
          }}
        />
      )}
    </section>
  );
}