import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Alert, App as AntdApp, Button, Checkbox, Pagination, Space, Tag, Typography } from 'antd';
import type { LoanStatus, NotificationType } from '../../../api/types';
import { ApiError } from '../../../api/client';
import { errorMessage } from '../../../api/errors';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { listMyNotifications, markAllNotificationsRead, markNotificationRead, notificationKeys, notificationQueryOptions } from '../api';
import '../notifications.css';

const typeLabels: Record<NotificationType, string> = { DUE_SOON: '到期提醒', OVERDUE: '逾期提醒', DUNNING: '催还通知' };
const statusLabels: Record<LoanStatus, string> = { ACTIVE: '借阅中', OVERDUE: '逾期未还', RETURNED: '已归还' };
const timestampFormatter = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' });

export default function NotificationInboxPage() {
  const { message } = AntdApp.useApp();
  const client = useQueryClient();
  const [page, setPage] = useState(0);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const writeLock = useRef(false);
  const params = { unreadOnly, page, size: 20 };
  const list = useQuery({
    ...notificationQueryOptions,
    queryKey: notificationKeys.list(params),
    queryFn: ({ signal }) => listMyNotifications(params, signal),
  });
  const read = useMutation({
    retry: false,
    mutationFn: async (id: number | 'all') => {
      if (id === 'all') await markAllNotificationsRead();
      else await markNotificationRead(id);
    },
    onSuccess: async (_, id) => {
      message.success(id === 'all' ? '已将全部提醒标为已读' : '已标为已读');
      // 未读过滤的最后一页可能被清空，回到第一页；历史通知仍保留。
      if (unreadOnly) setPage(0);
      await client.invalidateQueries({ queryKey: notificationKeys.all });
    },
    onError: (error) => {
      const code = error instanceof ApiError ? (error.status === 503 ? 'DB_BUSY' : error.code) : 'NETWORK_ERROR';
      message.error(errorMessage(code));
    },
    onSettled: () => { writeLock.current = false; },
  });
  const markRead = (id: number | 'all') => {
    if (writeLock.current || list.isFetching) return;
    writeLock.current = true;
    read.mutate(id);
  };

  return (
    <section className="notification-inbox" aria-labelledby="notification-heading">
      <div className="notification-heading">
        <div>
          <Typography.Title level={3} id="notification-heading" style={{ margin: 0 }}>我的提醒</Typography.Title>
          <Typography.Text type="secondary">借阅消息 · 从新到旧</Typography.Text>
        </div>
        <Button aria-label="全部标为已读" onClick={() => markRead('all')} loading={read.isPending && read.variables === 'all'} disabled={read.isPending || list.isFetching || !list.data || list.isError}>全部标为已读</Button>
      </div>
      <Alert type="info" showIcon title="站内提醒仅在登录系统后可查看" description="已读仅表示您在系统中标记过，不表示短信、邮件或电话已送达。" />
      <div className="notification-filter">
        <Checkbox checked={unreadOnly} disabled={read.isPending} onChange={(event) => { setUnreadOnly(event.target.checked); setPage(0); }}>仅看未读</Checkbox>
        <Typography.Text type="secondary">时间均为北京时间</Typography.Text>
      </div>
      {list.isPending ? <PageLoading tip="正在加载提醒…" /> : list.isError ? <PageError error={list.error} onRetry={() => void list.refetch()} /> : (
        <>
          {list.data.items.length === 0 ? <EmptyState description={unreadOnly ? '暂无未读提醒' : '暂无提醒'} /> : (
            <div className="notification-items" aria-busy={list.isFetching}>
              {list.data.items.map((notice) => (
                <article key={notice.id} className={`notification-item${notice.readAt === null ? ' notification-unread' : ''}`}>
                  <div className="notification-item-heading">
                    <Typography.Title level={4} style={{ margin: 0 }}>{notice.book.title}</Typography.Title>
                    <Space size="small" wrap><Tag color={notice.readAt === null ? 'processing' : 'default'}>{notice.readAt === null ? '未读' : '已读'}</Tag><Tag>{typeLabels[notice.type]}</Tag></Space>
                  </div>
                  <div className="notification-current-state">
                    <Tag color={notice.loanState.status === 'RETURNED' ? 'success' : notice.loanState.status === 'OVERDUE' ? 'error' : 'processing'}>当前状态：{statusLabels[notice.loanState.status]}</Tag>
                    <Typography.Text strong>当前应还日：{notice.loanState.currentDueDate}</Typography.Text>
                  </div>
                  {notice.loanState.status === 'RETURNED' ? <Alert type="success" showIcon title="该借阅已归还，无需再还书；以下为历史通知。" /> : notice.dueDate !== notice.loanState.currentDueDate ? <Alert type="info" showIcon title="应还日已调整，请以当前应还日为准；以下为历史通知。" /> : null}
                  <div className="notification-history">
                    <Typography.Text type="secondary">历史通知内容（生成时）</Typography.Text>
                    <Typography.Paragraph strong style={{ marginBlock: 8 }}>{notice.title}</Typography.Paragraph>
                    <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>{notice.content}</Typography.Paragraph>
                    <div className="notification-metadata">
                      <span>副本条码：<code>{notice.copyBarcode}</code></span>
                      <span>生成时应还日：{notice.dueDate}</span>
                      {notice.overdueDays !== null && <span>生成时逾期：{notice.overdueDays} 天</span>}
                      {notice.seq !== null && <span>提醒周期：{notice.seq}</span>}
                      <time dateTime={notice.createdAt}>生成时间：{timestampFormatter.format(new Date(notice.createdAt))}</time>
                    </div>
                  </div>
                  {notice.readAt === null && <Button size="small" aria-label="标为已读" onClick={() => markRead(notice.id)} disabled={read.isPending || list.isFetching} loading={read.isPending && read.variables === notice.id}>标为已读</Button>}
                </article>
              ))}
            </div>
          )}
          {list.data.totalItems > 0 && <Pagination className="notification-pagination" current={page + 1} pageSize={20} total={list.data.totalItems} showSizeChanger={false} disabled={read.isPending || list.isFetching} onChange={(nextPage) => setPage(nextPage - 1)} />}
        </>
      )}
    </section>
  );
}
