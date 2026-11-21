import { useQuery, useQueryClient } from '@tanstack/react-query';
import { Button, Modal, Space, Tag } from 'antd';
import type { DunningCaseSummary } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { dunningKeys, dunningQueryOptions, getDunningHistory } from '../api';
import { formatDunningTime, methodLabels, statusLabels } from '../presentation';

export function DunningHistoryModal({ item, onClose }: { item: DunningCaseSummary; onClose: () => void }) {
  const client = useQueryClient();
  const history = useQuery({
    ...dunningQueryOptions,
    queryKey: dunningKeys.history(item.loanId),
    queryFn: ({ signal }) => getDunningHistory(item.loanId, signal),
  });
  const refresh = () => { void client.invalidateQueries({ queryKey: dunningKeys.all }); };
  const data = history.data;
  return (
    <Modal open title={`催还历史 · 借阅 ${item.loanId}`} width={720} className="dunning-modal" onCancel={onClose} footer={<Space wrap><Button onClick={refresh} disabled={history.isFetching} aria-label="刷新历史">刷新历史</Button><Button onClick={onClose} aria-label="关闭历史">关闭历史</Button></Space>}>
      <div className="dunning-loan-summary"><strong>{item.book.title}</strong><div>{item.reader.name} · {item.reader.cardNo}</div><code>{item.copyBarcode}</code></div>
      <div className="dunning-help">备注仅管理员可见。以下状态和结案时间来自服务器；时间均为北京时间。</div>
      {history.isError ? <PageError error={history.error} onRetry={refresh} /> : history.isPending || history.isFetching ? <PageLoading tip="正在加载催还历史…" /> : data && (
        <>
          <div className="dunning-history-state">
            <Tag color={data.status === 'CLOSED' ? 'green' : 'gold'}>{statusLabels[data.status]}</Tag>
            <span>共 {data.records.length} 条记录</span>
            {data.closedAt !== null && <time dateTime={data.closedAt}>结案时间：{formatDunningTime(data.closedAt)}</time>}
            {data.closeReason === 'RETURNED' && <span>结案原因：已归还</span>}
          </div>
          {data.records.length === 0 ? <EmptyState description="该借阅暂无催还记录" /> : <ol className="dunning-history-records">
            {data.records.map(record => <li key={record.id}>
              <div className="dunning-record-head"><Tag>{methodLabels[record.method]}</Tag><time dateTime={record.createdAt}>{formatDunningTime(record.createdAt)}</time></div>
              <div>经办人：<strong>{record.operator.username}</strong></div>
              <div className="dunning-note">{record.note ?? '无备注'}</div>
            </li>)}
          </ol>}
        </>
      )}
    </Modal>
  );
}
