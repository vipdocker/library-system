// AC-SRCH-02：管理员与读者都能查看详情与可借数量；读者不请求副本列表、无管理入口。
// AC-BOOK-03：详情展示 totalCopies / availableCopies。AC-UI-01：加载 / 错误重试 / 404 态。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import type { AuthContextValue } from '../../../auth/AuthContext';
import { useAuth } from '../../../auth/AuthContext';
import { getBook, listCopies } from '../api';
import BookDetailPage from '../pages/BookDetailPage';
import { adminUser, makeBookDetail, readerUser, renderCatalog } from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, getBook: vi.fn(), listCopies: vi.fn() };
});

vi.mock('../../../auth/AuthContext', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../../auth/AuthContext')>();
  return { ...actual, useAuth: vi.fn() };
});

function loginAs(user: AuthContextValue['user']) {
  const value: AuthContextValue = {
    status: user ? 'authenticated' : 'anonymous',
    user,
    login: vi.fn(),
    logout: vi.fn(),
    markLoggedOut: vi.fn(),
  };
  vi.mocked(useAuth).mockReturnValue(value);
}

function renderDetail(route = '/books/5') {
  return renderCatalog(<BookDetailPage />, { route, path: '/books/:id' });
}

beforeEach(() => {
  loginAs(readerUser);
  vi.mocked(getBook).mockResolvedValue(makeBookDetail());
  vi.mocked(listCopies).mockResolvedValue([]);
});

describe('AC-SRCH-02 / AC-BOOK-03 BookDetailPage', () => {
  it('读者可见书目信息与可借数量，且不请求副本列表、无编辑入口', async () => {
    renderDetail();
    expect(await screen.findByRole('heading', { name: '深入理解计算机系统' })).toBeInTheDocument();
    expect(screen.getByText('册可借 / 馆藏 3 册')).toBeInTheDocument();
    expect(screen.getByText('9780306406157')).toBeInTheDocument();
    expect(screen.getByText('现在可借')).toBeInTheDocument();
    expect(screen.getByText('从程序员视角讲解计算机系统的经典教材。')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /编辑图书与副本/ })).not.toBeInTheDocument();
    expect(vi.mocked(listCopies)).not.toHaveBeenCalled();
  });

  it('管理员可见"编辑图书与副本"入口，链接到编辑页', async () => {
    loginAs(adminUser);
    renderDetail();
    const link = await screen.findByRole('link', { name: /编辑图书与副本/ });
    expect(link).toHaveAttribute('href', '/admin/books/5/edit');
  });

  it('无可借副本时显示"暂无可借副本"', async () => {
    vi.mocked(getBook).mockResolvedValue(makeBookDetail({ availableCopies: 0 }));
    renderDetail();
    expect(await screen.findByText('暂无可借副本')).toBeInTheDocument();
  });

  it('AC-UI-01 加载中显示加载提示', () => {
    vi.mocked(getBook).mockReturnValue(new Promise(() => {}));
    renderDetail();
    expect(screen.getByText('图书详情加载中…')).toBeInTheDocument();
  });

  it('AC-UI-01 404 显示"图书不存在或已被删除"并提供返回入口', async () => {
    vi.mocked(getBook).mockRejectedValue(new ApiError(404, 'BOOK_NOT_FOUND', 'gone'));
    renderDetail();
    expect(await screen.findByText('图书不存在或已被删除')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /回到图书检索/ })).toHaveAttribute('href', '/books');
  });

  it('AC-UI-01 503 显示"系统繁忙"，重试后恢复', async () => {
    vi.mocked(getBook)
      .mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'busy'))
      .mockResolvedValue(makeBookDetail());
    renderDetail();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByRole('heading', { name: '深入理解计算机系统' })).toBeInTheDocument();
  });
});

describe('AC-S3/T3 书目与封面来源分别标注', () => {
  it('ShowAPI 书目来源 + 本地封面：链接文案为 ShowAPI，封面为本地上传', async () => {
    vi.mocked(getBook).mockResolvedValue(makeBookDetail({
      sourceProvider: 'SHOWAPI', sourceIsbn: '9780306406157',
      metadataSourceUrl: 'https://www.showapi.com/apis/isbn-book-1626',
      cover: { url: '/api/books/5/cover?v=sha', sha256: 'sha', mediaType: 'image/png', byteLength: 4, width: 1, height: 1, sourceUrl: null, sourceProvider: null, sourceIsbn: null },
    }));
    renderDetail();
    const link = await screen.findByRole('link', { name: 'ShowAPI · 9780306406157' });
    expect(link).toHaveAttribute('href', 'https://www.showapi.com/apis/isbn-book-1626');
    expect(screen.getByText('本地上传')).toBeInTheDocument();
    expect(screen.queryByText(/Open Library/)).not.toBeInTheDocument();
  });
  it('OL 书目 + ShowAPI 封面：两个来源独立标注，不互相沿用', async () => {
    vi.mocked(getBook).mockResolvedValue(makeBookDetail({
      sourceProvider: 'OPEN_LIBRARY', sourceIsbn: '9780306406157', openLibraryIsbn: '9780306406157',
      metadataSourceUrl: 'https://openlibrary.org/isbn/9780306406157',
      cover: { url: '/api/books/5/cover?v=sha', sha256: 'sha', mediaType: 'image/png', byteLength: 4, width: 1, height: 1, sourceUrl: 'https://www.showapi.com/apis/isbn-book-1626', sourceProvider: 'SHOWAPI', sourceIsbn: '9780306406157' },
    }));
    renderDetail();
    expect(await screen.findByRole('link', { name: 'Open Library · 9780306406157' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'ShowAPI' })).toHaveAttribute('href', 'https://www.showapi.com/apis/isbn-book-1626');
  });
  it('旧数据仅有 openLibraryIsbn/sourceUrl 时仍按 Open Library 标注（兼容回归）', async () => {
    vi.mocked(getBook).mockResolvedValue(makeBookDetail({
      openLibraryIsbn: '9780306406157', metadataSourceUrl: 'https://openlibrary.org/isbn/9780306406157',
      cover: { url: '/api/books/5/cover?v=sha', sha256: 'sha', mediaType: 'image/png', byteLength: 4, width: 1, height: 1, sourceUrl: 'https://openlibrary.org/isbn/9780306406157', sourceProvider: null, sourceIsbn: null },
    }));
    renderDetail();
    expect(await screen.findByRole('link', { name: 'Open Library · 9780306406157' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Open Library' })).toBeInTheDocument();
  });
});