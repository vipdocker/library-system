// 管理员仪表盘（设计 10.3 节）：概览数字卡片 + 最近 7 天借还趋势（CSS 条形，无图表库）+ 逾期快捷入口。
// 页面状态遵循 10.2 节：加载 / 空态 / 错误（带重试，503 显示“系统繁忙”）/ 有数据。
import { Link } from 'react-router';
import { Button, Skeleton, Statistic } from 'antd';
import {
  AlertOutlined,
  ArrowRightOutlined,
  BookOutlined,
  CheckCircleOutlined,
  InboxOutlined,
  LineChartOutlined,
  ReadOutlined,
  StopOutlined,
  SwapOutlined,
  TeamOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';
import type { ReactNode } from 'react';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { fetchLoanSeries, fetchStatsOverview } from '../api';
import { defaultRange } from '../range';
import { TrendChart } from '../components/TrendChart';
import '../stats.css';

interface DashCardProps {
  label: string;
  value: number;
  icon: ReactNode;
  tone: 'green' | 'blue' | 'amber' | 'red';
}

function DashCard({ label, value, icon, tone }: DashCardProps) {
  return (
    <div className={`dash-card dash-card--${tone}`}>
      <span className="dash-card-label">
        {icon}
        {label}
      </span>
      <Statistic value={value} />
    </div>
  );
}

export default function DashboardPage() {
  const overviewQuery = useQuery({
    queryKey: ['stats', 'overview'],
    queryFn: ({ signal }) => fetchStatsOverview(signal),
  });

  const trendRange = defaultRange(7);
  const trendQuery = useQuery({
    queryKey: ['stats', 'loans', trendRange.from, trendRange.to, 'DAY'],
    queryFn: ({ signal }) => fetchLoanSeries({ ...trendRange, granularity: 'DAY' }, signal),
    placeholderData: keepPreviousData,
  });

  const overview = overviewQuery.data;
  const points = trendQuery.data;
  const trendEmpty = !!points && points.every((p) => p.borrowCount === 0 && p.returnCount === 0);

  return (
    <section className="stats-page" aria-label="管理员仪表盘">
      <header className="dash-hero">
        <div>
          <h3>仪表盘</h3>
          <div className="dash-hero-date">{dayjs().locale('zh-cn').format('YYYY 年 M 月 D 日 dddd')}</div>
        </div>
        <div className="dash-hero-links">
          <Link to="/admin/stats">
            <Button icon={<LineChartOutlined />}>借阅统计</Button>
          </Link>
          <Link to="/admin/reports/overdue">
            <Button danger icon={<AlertOutlined />}>
              逾期报表
            </Button>
          </Link>
        </div>
      </header>

      {overviewQuery.isPending ? (
        <div className="stats-section" data-testid="dashboard-loading">
          <Skeleton active paragraph={{ rows: 4 }} />
        </div>
      ) : overviewQuery.isError ? (
        <PageError error={overviewQuery.error} onRetry={() => overviewQuery.refetch()} />
      ) : overview ? (
        <>
          <div className="dash-grid" role="list" aria-label="馆藏概览">
            <DashCard label="图书种类" value={overview.totalBooks} icon={<BookOutlined />} tone="green" />
            <DashCard label="副本总数" value={overview.totalCopies} icon={<InboxOutlined />} tone="green" />
            <DashCard label="可借副本" value={overview.availableCopies} icon={<CheckCircleOutlined />} tone="green" />
            <DashCard label="借出副本" value={overview.borrowedCopies} icon={<ReadOutlined />} tone="blue" />
            <DashCard label="不可用副本" value={overview.unavailableCopies} icon={<StopOutlined />} tone="amber" />
          </div>
          <div className="dash-grid" role="list" aria-label="读者与流通概览">
            <DashCard label="读者总数" value={overview.totalReaders} icon={<TeamOutlined />} tone="blue" />
            <DashCard label="活跃读者" value={overview.activeReaders} icon={<UserOutlined />} tone="blue" />
            <DashCard label="当前在借" value={overview.activeLoans} icon={<SwapOutlined />} tone="blue" />
            <Link to="/admin/reports/overdue" className="dash-card-link" aria-label="查看逾期报表">
              <div className="dash-card dash-card--red">
                <span className="dash-card-label">
                  <AlertOutlined />
                  逾期未还
                </span>
                <Statistic value={overview.overdueLoans} />
                <span className="dash-card-cta">
                  查看逾期报表 <ArrowRightOutlined />
                </span>
              </div>
            </Link>
            <DashCard label="今日借出" value={overview.borrowsToday} icon={<ReadOutlined />} tone="green" />
            <DashCard label="今日归还" value={overview.returnsToday} icon={<CheckCircleOutlined />} tone="green" />
          </div>
        </>
      ) : null}

      <div className="stats-section">
        <div className="stats-section-head">
          <h4 className="stats-section-title">最近 7 天借还趋势</h4>
          <Link to="/admin/stats" className="stats-page-sub">
            查看完整统计 <ArrowRightOutlined />
          </Link>
        </div>
        {trendQuery.isPending ? (
          <PageLoading tip="趋势加载中…" />
        ) : trendQuery.isError ? (
          <PageError error={trendQuery.error} onRetry={() => trendQuery.refetch()} />
        ) : trendEmpty ? (
          <EmptyState
            description="最近 7 天还没有借还记录"
            action={
              <Link to="/admin/circulation">
                <Button type="primary">去流通柜台办理借阅</Button>
              </Link>
            }
          />
        ) : points ? (
          <TrendChart points={points} granularity="DAY" compact />
        ) : null}
      </div>
    </section>
  );
}