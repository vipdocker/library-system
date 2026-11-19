// AC-UI-01：公共状态组件（PageLoading / PageError 带重试 / EmptyState）。
import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ApiError } from '../../api/client';
import { EmptyState } from '../EmptyState';
import { PageError } from '../PageError';
import { PageLoading } from '../PageLoading';

describe('AC-UI-01 公共状态组件', () => {
  it('PageLoading 显示加载提示', () => {
    render(<PageLoading tip="加载中…" />);
    expect(screen.getByText('加载中…')).toBeInTheDocument();
  });

  it('PageError 显示错误码中文文案，点击"重试"调用 onRetry', () => {
    const onRetry = vi.fn();
    render(<PageError error={new ApiError(409, 'COPY_NOT_AVAILABLE', 'x')} onRetry={onRetry} />);
    expect(screen.getByText('副本不可借（已借出或不可用）')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('PageError 对 503 DB_BUSY 显示"系统繁忙"', () => {
    render(<PageError error={new ApiError(503, 'DB_BUSY', 'x')} onRetry={() => {}} />);
    expect(screen.getByText('系统繁忙，请稍后再试')).toBeInTheDocument();
  });

  it('PageError 对非 ApiError（如网络异常）显示 NETWORK_ERROR 文案', () => {
    render(<PageError error={new Error('boom')} />);
    expect(screen.getByText('网络异常，请检查连接后重试')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /重\s*试/ })).not.toBeInTheDocument();
  });

  it('EmptyState 支持引导文案与操作按钮', () => {
    render(<EmptyState description="没有找到匹配的图书" action={<button>新建图书</button>} />);
    expect(screen.getByText('没有找到匹配的图书')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '新建图书' })).toBeInTheDocument();
  });
});