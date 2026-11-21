import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Alert, Button, Pagination, Radio, Tag } from 'antd';
import type { DunningCaseSummary, DunningStatus } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { dunningKeys, dunningQueryOptions, listDunningCases } from '../api';
import { DunningHistoryModal } from '../components/DunningHistoryModal';
import { formatDunningTime, statusLabels } from '../presentation';
import '../dunning.css';

export default function DunningCaseListPage() {
  const [pagination, setPagination] = useState({ page: 0, size: 20 });
  const [status, setStatus] = useState<DunningStatus | undefined>();
  const [selected, setSelected] = useState<DunningCaseSummary | null>(null);
  const params = { status, ...pagination };
  const list = useQuery({ ...dunningQueryOptions, queryKey: dunningKeys.list(params), queryFn: ({ signal }) => listDunningCases(params, signal) });
  const data = list.data;

  return (
    <section className="dunning-page" aria-labelledby="dunning-heading">
      <header className="dunning-heading"><div><h3 id="dunning-heading">催还记录</h3><div className="dunning-help">借阅案件 · 按最近登记时间从新到旧</div></div><Button onClick={() => void list.refetch()} disabled={list.isFetching} aria-label="刷新案件">刷新案件</Button></header>
      <Alert type="info" showIcon title="电话、当面、站内、其他仅为登记方式，不代表系统已送达" description="登记时生成固定模板站内提醒，仅在读者登录后可查看。请从逾期报表登记；归还后自动结案，历史记录保留。" />
      <Radio.Group value={status ?? 'ALL'} disabled={list.isFetching} onChange={event => { setStatus(event.target.value === 'ALL' ? undefined : event.target.value as DunningStatus); setPagination(prev => ({ ...prev, page: 0 })); }} options={[{ value: 'ALL', label: '全部案件' }, { value: 'OPEN', label: '仅未结案' }, { value: 'CLOSED', label: '仅已结案' }]} />
      {list.isError ? <PageError error={list.error} onRetry={() => void list.refetch()} /> : list.isPending || list.isFetching ? <PageLoading tip="正在加载催还案件…" /> : data && (
        <>
          {data.items.length === 0 ? <EmptyState description="暂无催还案件" /> : <div className="dunning-cases">
            {data.items.map(item => <article key={item.loanId} className="dunning-case">
              <div className="dunning-case-head"><h4>{item.book.title}</h4><Tag color={item.status === 'CLOSED' ? 'green' : 'gold'}>{statusLabels[item.status]}</Tag></div>
              <div className="dunning-case-meta"><span>{item.reader.name} · {item.reader.cardNo}</span><span>副本：<code>{item.copyBarcode}</code></span><span>借阅号：{item.loanId}</span><span>应还日：{item.dueDate}</span></div>
              <div className="dunning-case-meta"><strong>{item.recordCount} 次</strong><time dateTime={item.lastRecordedAt}>最近登记：{formatDunningTime(item.lastRecordedAt)}</time>{item.closedAt !== null && <time dateTime={item.closedAt}>结案时间：{formatDunningTime(item.closedAt)}</time>}{item.closeReason === 'RETURNED' && <span>结案原因：已归还</span>}</div>
              <Button onClick={() => setSelected(item)} aria-label="查看历史">查看历史</Button>
            </article>)}
          </div>}
          {data.totalItems > 0 && <Pagination current={pagination.page + 1} pageSize={pagination.size} total={data.totalItems} showSizeChanger pageSizeOptions={[10, 20, 50, 100]} showTotal={total => `共 ${total} 个案件`} onChange={(page, size) => setPagination({ page: size === pagination.size ? page - 1 : 0, size })} />}
        </>
      )}
      {selected && <DunningHistoryModal item={selected} onClose={() => setSelected(null)} />}
    </section>
  );
}
