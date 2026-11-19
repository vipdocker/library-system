// 借还趋势：纯 CSS 条形图（设计 10.3 节约束——不引入图表库）。
// 借出 = 品牌墨绿，归还 = 信息蓝；每个周期两根条形，悬停显示精确数值，
// 长区间横向滚动（滚动条样式由全局 CSS 统一）。
import type { LoanSeriesPoint, StatsGranularity } from '../../../api/types';

export interface TrendChartProps {
  points: LoanSeriesPoint[];
  granularity: StatsGranularity;
  /** 紧凑模式（仪表盘 7 天）：更矮、显示每根条形数值 */
  compact?: boolean;
}

/** 把最大值取整到 1/2/5×10^k 再向上取 4 的倍数，保证网格线标签是整数。 */
function niceMax(value: number): number {
  if (value <= 4) return 4;
  const pow = 10 ** Math.floor(Math.log10(value));
  for (const step of [1, 2, 5, 10]) {
    const candidate = step * pow;
    if (candidate >= value) return Math.ceil(candidate / TICKS) * TICKS;
  }
  return Math.ceil((10 * pow) / TICKS) * TICKS;
}

function periodLabel(period: string, granularity: StatsGranularity): string {
  // DAY: YYYY-MM-DD → MM-DD；MONTH: YYYY-MM 原样
  return granularity === 'DAY' ? period.slice(5) : period;
}

const TICKS = 4;

export function TrendChart({ points, granularity, compact = false }: TrendChartProps) {
  const rawMax = Math.max(1, ...points.map((p) => Math.max(p.borrowCount, p.returnCount)));
  const max = niceMax(rawMax);
  const plotHeight = compact ? 150 : 220;
  // 标签最多显示约 14 个，避免长区间拥挤
  const labelStep = Math.max(1, Math.ceil(points.length / 14));
  const totalBorrow = points.reduce((sum, p) => sum + p.borrowCount, 0);
  const totalReturn = points.reduce((sum, p) => sum + p.returnCount, 0);

  return (
    <figure
      className={`trend-chart${compact ? ' trend-chart--compact' : ''}`}
      role="img"
      aria-label={`借还趋势图：共 ${points.length} 个周期，借出合计 ${totalBorrow} 次，归还合计 ${totalReturn} 次`}
    >
      <div className="trend-legend" aria-hidden="true">
        <span className="trend-legend-item trend-legend-item--borrow">借出</span>
        <span className="trend-legend-item trend-legend-item--return">归还</span>
      </div>
      <div className="trend-body">
        <div className="trend-yaxis" style={{ height: plotHeight }} aria-hidden="true">
          {Array.from({ length: TICKS + 1 }, (_, i) => {
            const value = Math.round((max / TICKS) * (TICKS - i));
            return (
              <span key={i} className="trend-yaxis-label">
                {value}
              </span>
            );
          })}
        </div>
        <div className="trend-scroll">
          <div className="trend-canvas" style={{ minWidth: points.length * (compact ? 44 : 30) }}>
            <div className="trend-plot" style={{ height: plotHeight }}>
              {Array.from({ length: TICKS + 1 }, (_, i) => (
                <span
                  key={i}
                  className="trend-gridline"
                  style={{ bottom: `${(i / TICKS) * 100}%` }}
                  aria-hidden="true"
                />
              ))}
              <div className="trend-groups">
                {points.map((p, index) => (
                  <div
                    key={p.period}
                    className="trend-group"
                    title={`${p.period}：借出 ${p.borrowCount} 次 · 归还 ${p.returnCount} 次`}
                  >
                    <div className="trend-bar-pair">
                      <div className="trend-bar-slot">
                        {compact && p.borrowCount > 0 && (
                          <span className="trend-bar-value">{p.borrowCount}</span>
                        )}
                        <div
                          className="trend-bar trend-bar--borrow"
                          style={{
                            height: `${(p.borrowCount / max) * 100}%`,
                            minHeight: p.borrowCount > 0 ? 3 : 0,
                            animationDelay: `${Math.min(index, 20) * 24}ms`,
                          }}
                        />
                      </div>
                      <div className="trend-bar-slot">
                        {compact && p.returnCount > 0 && (
                          <span className="trend-bar-value">{p.returnCount}</span>
                        )}
                        <div
                          className="trend-bar trend-bar--return"
                          style={{
                            height: `${(p.returnCount / max) * 100}%`,
                            minHeight: p.returnCount > 0 ? 3 : 0,
                            animationDelay: `${Math.min(index, 20) * 24 + 60}ms`,
                          }}
                        />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
            <div className="trend-labels" aria-hidden="true">
              {points.map((p, index) => (
                <span key={p.period} className="trend-label">
                  {index % labelStep === 0 ? periodLabel(p.period, granularity) : ''}
                </span>
              ))}
            </div>
          </div>
        </div>
      </div>
    </figure>
  );
}