// AC-UI-01：检索页四种状态（加载 / 空 / 错误+重试 / 有数据）。
// AC-SRCH-02：检索条件写入 URL、刷新（初始 URL）后保留；AC-BOOK-03：可借/馆藏数量展示。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import { DEFAULT_BOOK_QUERY, listCategories, searchBooks } from '../api';
import BookSearchPage from '../pages/BookSearchPage';
import {
  categoriesFixture,
  makeBook,
  makePage,
  renderCatalog,
} from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    listCategories: vi.fn(),
    searchBooks: vi.fn(),
  };
});

const booksPage = makePage(
  [makeBook(), makeBook({ id: 6, title: '三体', author: '刘慈欣', availableCopies: 0, totalCopies: 1 })],
  { totalItems: 45, totalPages: 3 },
);

function renderSearch(route = '/books') {
  return renderCatalog(<BookSearchPage />, { route, path: '/books' });
}

beforeEach(() => {
  vi.mocked(listCategories).mockResolvedValue(categoriesFixture);
  vi.mocked(searchBooks).mockResolvedValue(booksPage);
});

describe('AC-UI-01 BookSearchPage 四种状态', () => {
  it('加载中：首次加载显示加载提示', () => {
    vi.mocked(searchBooks).mockReturnValue(new Promise(() => {}));
    renderSearch();
    expect(screen.getByText('图书加载中…')).toBeInTheDocument();
  });

  it('空态：无结果时提示"没有找到匹配的图书"', async () => {
    vi.mocked(searchBooks).mockResolvedValue(makePage([]));
    renderSearch();
    expect(await screen.findByText('没有找到匹配的图书')).toBeInTheDocument();
  });

  it('错误：503 显示"系统繁忙"，点击重试后恢复数据', async () => {
    vi.mocked(searchBooks)
      .mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'busy'))
      .mockResolvedValue(booksPage);
    renderSearch();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByRole('link', { name: '深入理解计算机系统' })).toBeInTheDocument();
    expect(vi.mocked(searchBooks)).toHaveBeenCalledTimes(2);
  });

  it('AC-BOOK-03 有数据：显示书目与可借/馆藏数量', async () => {
    renderSearch();
    expect(await screen.findByRole('link', { name: '深入理解计算机系统' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '三体' })).toBeInTheDocument();
    expect(screen.getByText('2 / 3')).toBeInTheDocument();
    expect(screen.getByText('0 / 1')).toBeInTheDocument();
    expect(screen.getByText('无可借')).toBeInTheDocument();
    expect(screen.getByText('共找到 45 本图书')).toBeInTheDocument();
  });
});

describe('AC-SRCH-02 检索条件与 URL 同步', () => {
  it('初始 URL 中的条件被解析并传给接口（刷新后条件不丢失）', async () => {
    renderSearch('/books?q=%E4%B8%89%E4%BD%93&field=title&categoryId=2&availableOnly=true&page=1&size=10&sort=author,desc');
    await waitFor(() => {
      expect(vi.mocked(searchBooks)).toHaveBeenCalledWith({
        q: '三体',
        field: 'title',
        categoryId: 2,
        availableOnly: true,
        page: 1,
        size: 10,
        sort: 'author,desc',
      });
    });
    expect(screen.getByLabelText('检索关键词')).toHaveValue('三体');
  });

  it('默认进入时使用默认查询参数', async () => {
    renderSearch();
    await waitFor(() => {
      expect(vi.mocked(searchBooks)).toHaveBeenCalledWith(DEFAULT_BOOK_QUERY);
    });
  });

  it('提交检索把 q 写入 URL 并重置页码', async () => {
    renderSearch('/books?page=2');
    await screen.findByRole('link', { name: '深入理解计算机系统' });
    const input = screen.getByLabelText('检索关键词');
    await userEvent.clear(input);
    await userEvent.type(input, '算法');
    await userEvent.click(screen.getByRole('button', { name: /检\s*索/ }));
    await waitFor(() => {
      const probe = screen.getByTestId('location-probe').textContent ?? '';
      expect(probe).toContain('q=%E7%AE%97%E6%B3%95');
      expect(probe).not.toContain('page=');
    });
    await waitFor(() => {
      expect(vi.mocked(searchBooks)).toHaveBeenLastCalledWith(
        expect.objectContaining({ q: '算法', page: 0 }),
      );
    });
  });

  it('勾选"仅显示可借"写入 URL 并重新查询', async () => {
    renderSearch();
    await screen.findByRole('link', { name: '深入理解计算机系统' });
    await userEvent.click(screen.getByRole('checkbox', { name: /仅显示可借/ }));
    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toContain('availableOnly=true');
    });
    await waitFor(() => {
      expect(vi.mocked(searchBooks)).toHaveBeenLastCalledWith(
        expect.objectContaining({ availableOnly: true, page: 0 }),
      );
    });
  });

  it('翻页写入 URL page（从 0 起）', async () => {
    renderSearch();
    await screen.findByRole('link', { name: '深入理解计算机系统' });
    await userEvent.click(within(screen.getByTitle('2')).getByRole('button'));
    await waitFor(() => {
      expect(screen.getByTestId('location-probe').textContent).toContain('page=1');
    });
    await waitFor(() => {
      expect(vi.mocked(searchBooks)).toHaveBeenLastCalledWith(
        expect.objectContaining({ page: 1 }),
      );
    });
  });

  it('非法 URL 参数回退到默认值，不向后端发送越界参数', async () => {
    renderSearch('/books?field=hack&sort=drop,desc&page=-3&size=999&categoryId=abc');
    await waitFor(() => {
      expect(vi.mocked(searchBooks)).toHaveBeenCalledWith(DEFAULT_BOOK_QUERY);
    });
  });
});