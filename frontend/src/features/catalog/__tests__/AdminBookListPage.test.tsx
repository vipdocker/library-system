// AC-BOOK-03 / AC-UI-01 / AC-UI-02：管理员图书列表的数据、空态引导、错误重试、
// 删除二次确认与 409（借阅历史）中文文案、新建入口。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import { deleteBook, listCategories, searchBooks } from '../api';
import AdminBookListPage from '../pages/AdminBookListPage';
import { categoriesFixture, makeBook, makePage, renderCatalog } from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    listCategories: vi.fn(),
    searchBooks: vi.fn(),
    deleteBook: vi.fn(),
  };
});

function renderPage() {
  return renderCatalog(<AdminBookListPage />, { route: '/admin/books', path: '/admin/books' });
}

beforeEach(() => {
  vi.mocked(listCategories).mockResolvedValue(categoriesFixture);
  vi.mocked(searchBooks).mockResolvedValue(makePage([makeBook()]));
  vi.mocked(deleteBook).mockResolvedValue(undefined);
});

describe('AC-UI-01 AdminBookListPage 状态', () => {
  it('有数据：显示书目、可借数量与编辑/删除操作', async () => {
    renderPage();
    expect(await screen.findByRole('link', { name: '深入理解计算机系统' })).toBeInTheDocument();
    expect(screen.getByText('2 / 3')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /编\s*辑/ })).toHaveAttribute(
      'href',
      '/admin/books/5/edit',
    );
    expect(screen.getByRole('button', { name: /删\s*除$/ })).toBeInTheDocument();
  });

  it('空态提供"新建图书"引导按钮', async () => {
    vi.mocked(searchBooks).mockResolvedValue(makePage([]));
    renderPage();
    expect(await screen.findByText('馆藏中还没有图书')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /新建图书/ }).length).toBeGreaterThanOrEqual(2);
  });

  it('错误显示"系统繁忙"，重试后恢复', async () => {
    vi.mocked(searchBooks)
      .mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'busy'))
      .mockResolvedValue(makePage([makeBook()]));
    renderPage();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByRole('link', { name: '深入理解计算机系统' })).toBeInTheDocument();
  });

  it('检索提交后按关键词重新查询', async () => {
    renderPage();
    await screen.findByRole('link', { name: '深入理解计算机系统' });
    await userEvent.type(screen.getByLabelText('检索关键词'), '计算机');
    await userEvent.click(screen.getByRole('button', { name: /检\s*索/ }));
    await waitFor(() => {
      expect(vi.mocked(searchBooks)).toHaveBeenLastCalledWith(
        expect.objectContaining({ q: '计算机', page: 0 }),
      );
    });
  });
});

describe('AC-UI-02 / AC-BOOK-02（UI 侧）删除图书', () => {
  it('删除需二次确认，成功后提示并刷新', async () => {
    renderPage();
    await screen.findByRole('link', { name: '深入理解计算机系统' });
    await userEvent.click(screen.getByRole('button', { name: /删\s*除$/ }));
    expect(vi.mocked(deleteBook)).not.toHaveBeenCalled();
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));
    await waitFor(() => {
      expect(vi.mocked(deleteBook)).toHaveBeenCalledWith(5);
    });
    expect(await screen.findByText('图书已删除，其副本一并删除')).toBeInTheDocument();
  });

  it('有借阅历史的图书删除失败：409 中文文案，列表不变', async () => {
    vi.mocked(deleteBook).mockRejectedValue(new ApiError(409, 'BOOK_HAS_LOAN_HISTORY', 'hist'));
    renderPage();
    await screen.findByRole('link', { name: '深入理解计算机系统' });
    await userEvent.click(screen.getByRole('button', { name: /删\s*除$/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));
    expect(await screen.findByText('该图书存在借阅历史，不能删除')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '深入理解计算机系统' })).toBeInTheDocument();
  });

  it('有在借副本的图书删除失败：BOOK_HAS_ACTIVE_LOANS 中文文案', async () => {
    vi.mocked(deleteBook).mockRejectedValue(new ApiError(409, 'BOOK_HAS_ACTIVE_LOANS', 'active'));
    renderPage();
    await screen.findByRole('link', { name: '深入理解计算机系统' });
    await userEvent.click(screen.getByRole('button', { name: /删\s*除$/ }));
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));
    expect(await screen.findByText('该图书有在借副本，不能删除')).toBeInTheDocument();
  });
});