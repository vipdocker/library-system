// 借阅统计页（设计 8.4 / 10.3 节）：日/月粒度切换、日期区间、热门图书；不引入图表库。
// 条件写入 URL query（from/to/granularity/limit），刷新后保留。
// 非法区间（颠倒、跨度 > 366 天、格式错误）在前端先校验并提示，不发请求；
// 服务端 400 仍按 errors.ts 的 VALIDATION_FAILED 文案显示。
import { useMemo } from 'react';
import { Alert, DatePicker, Segmented, Select, Spin, Table } from 'antd';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import type { StatsGranularity } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { fetchLoanSeries, fetchTopBooks } from '../api';
import { DATE_FORMAT, defaultRange, parseDate, validateRange } from '../range';
import { TrendChart } from '../components/TrendChart';
import { TopBooksList } from '../components/TopBooksList';
import '../stats.css';

const { RangePicker } = DatePicker;

const LIMIT_OPTIONS = [5, 10, 20, 50];
const GRANULARITY_OPTIONS = [
  { label: '按日', value: 'DAY' },
  { label: '按月', value: 'MONTH' },
];
const VIEW_OPTIONS = [
  { label: '图表', value: 'chart' },
  { label: '数据表', value: 'table' },
];

export default function StatsPage() {
  const [searchParams, setSearchParams] = useSearchParams();

  const fallback = useMemo(() => defaultRange(30), []);
  const from = searchParams.get('from') ?? fallback.from;
  const to = searchParams.get('to') ?? fallback.to;
  const granularity: StatsGranularity = searchParams.get('granularity') === 'MONTH' ? 'MONTH' : 'DAY';
  const limitParam = Number(searchParams.get('limit'));
  const limit = LIMIT_OPTIONS.includes(limitParam) ? limitParam : 10;
  const view = searchParams.get('view') === 'table' ? 'table' : 'chart';

  const rangeError = validateRange(from, to);
  const fromDayjs = parseDate(from);
  const toDayjs = parseDate(to);

  function updateParams(patch: Record<string, string>) {
    setSearchParams(
      (prev) => {
        const next = new URLSearchParams(prev);
        for (const [key, value] of Object.entries(patch)) next.set(key, value);
        return next;
      },
      { replace: true },
    );
  }

  const seriesQuery = useQuery({
    queryKey: ['stats', 'loans', from, to, granularity],
    queryFn: ({ signal }) => fetchLoanSeries({ from, to, granularity }, signal),
    enabled: !rangeError,
    placeholderData: keepPreviousData,
  });

  const topQuery = useQuery({
    queryKey: ['stats', 'top-books', from, to, limit],
    queryFn: ({ signal }) => fetchTopBooks({ from, to, limit }, signal),
    enabled: !rangeError,
    placeholderData: keepPreviousData,
  });

  const points = seriesQuery.data;
  const topBooks = topQuery.data;
  const totalBorrow = points?.reduce((sum, p) => sum + p.borrowCount, 0) ?? 0;
  const totalReturn = points?.reduce((sum, p) => sum + p.returnCount, 0) ?? 0;
  const seriesEmpty = !!points && totalBorrow === 0 && totalReturn === 0;
  const peak = points?.reduce(
    (best, p) => (p.borrowCount > best.borrowCount ? p : best),
    points[0] ?? { period: '-', borrowCount: 0, returnCount: 0 },
  );

  return (
    <section className="stats-page" aria-label="借阅统计">
      <header className="stats-page-head">
        <div>
          <h3>借阅统计</h3>
          <div className="stats-page-sub">按周期查看借出与归还数量，以及区间内最受欢迎的图书</div>
        </div>
      </header>

      <div className="stats-section">
        <div className="stats-controls">
          <div className="stats-control">
            <span className="stats-control-label" id="stats-granularity-label">粒度</span>
            <Segmented
              aria-label="统计粒度"
              options={GRANULARITY_OPTIONS}
              value={granularity}
              onChange={(value) => updateParams({ granularity: String(value) })}
            />
          </div>
          <div className="stats-control">
            <span className="stats-control-label">区间</span>
            <RangePicker
              aria-label="统计日期区间"
              allowClear={false}
              format={DATE_FORMAT}
              placeholder={['开始日期', '结束日期']}
              value={fromDayjs && toDayjs ? [fromDayjs, toDayjs] : null}
              onChange={(_dates, dateStrings) => {
                if (dateStrings[0] && dateStrings[1]) {
                  updateParams({ from: dateStrings[0], to: dateStrings[1] });
                }
              }}
            />
          </div>
          <div className="stats-control">
            <span className="stats-control-label">热门图书</span>
            <Select
              aria-label="热门图书数量"
              style={{ width: 96 }}
              value={limit}
              options={LIMIT_OPTIONS.map((n) => ({ label: `前 ${n} 本`, value: n }))}
              onChange={(value) => updateParams({ limit: String(value) })}
            />
          </div>
        </div>
        {rangeError && (
          <Alert
            style={{ marginTop: 14 }}
            type="error"
            showIcon
            title="日期区间不合法"
            description={rangeError}
          />
        )}
      </div>

      {!rangeError && (
        <>
          <div className="stats-section">
            <div className="stats-section-head">
              <h4 className="stats-section-title">
                借还趋势
                {seriesQuery.isFetching && <Spin size="small" aria-label="趋势刷新中" />}
              </h4>
              <div className="stats-control">
                {points && !seriesEmpty && (
                  <div className="trend-summary">
                    <span className="trend-summary-chip">
                      借出合计 <strong>{totalBorrow}</strong>
                    </span>
                    <span className="trend-summary-chip">
                      归还合计 <strong>{totalReturn}</strong>
                    </span>
                    <span className="trend-summary-chip">
                      借出峰值 <strong>{peak ? `${peak.period}（${peak.borrowCount}）` : '—'}</strong>
                    </span>
                  </div>
                )}
                <Segmented
                  size="small"
                  aria-label="趋势展示方式"
                  options={VIEW_OPTIONS}
                  value={view}
                  onChange={(value) => updateParams({ view: String(value) })}
                />
              </div>
            </div>
            {seriesQuery.isPending ? (
              <PageLoading tip="统计加载中…" />
            ) : seriesQuery.isError ? (
              <PageError error={seriesQuery.error} onRetry={() => seriesQuery.refetch()} />
            ) : seriesEmpty ? (
              <EmptyState description="该区间内没有借阅记录，换一个日期区间试试" />
            ) : points ? (
              view === 'chart' ? (
                <TrendChart points={points} granularity={granularity} />
              ) : (
                <Table
                  size="small"
                  rowKey="period"
                  dataSource={points}
                  pagination={false}
                  scroll={points.length > 15 ? { y: 360 } : undefined}
                  columns={[
                    { title: '周期', dataIndex: 'period' },
                    { title: '借出数', dataIndex: 'borrowCount', align: 'right' },
                    { title: '归还数', dataIndex: 'returnCount', align: 'right' },
                  ]}
                />
              )
            ) : null}
          </div>

          <div className="stats-section">
            <div className="stats-section-head">
              <h4 className="stats-section-title">
                热门图书
                {topQuery.isFetching && <Spin size="small" aria-label="热门图书刷新中" />}
              </h4>
              <span className="stats-page-sub">按区间内借阅次数排序</span>
            </div>
            {topQuery.isPending ? (
              <PageLoading tip="热门图书加载中…" />
            ) : topQuery.isError ? (
              <PageError error={topQuery.error} onRetry={() => topQuery.refetch()} />
            ) : topBooks && topBooks.length === 0 ? (
              <EmptyState description="该区间内没有借阅记录，暂无热门图书" />
            ) : topBooks ? (
              <TopBooksList books={topBooks} />
            ) : null}
          </div>
        </>
      )}
    </section>
  );
}