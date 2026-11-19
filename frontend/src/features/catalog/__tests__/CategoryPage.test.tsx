// AC-CAT-01：分类新建 / 编辑 / 删除，bookCount 展示，409 中文文案（重名映射到字段、被引用不可删）。
// AC-UI-01：加载 / 空态 / 错误重试 / 有数据。AC-UI-02：必填校验、二次确认。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import { createCategory, deleteCategory, listCategories, updateCategory } from '../api';
import CategoryPage from '../pages/CategoryPage';
import { categoriesFixture, renderCatalog } from './testUtils';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return {
    ...actual,
    listCategories: vi.fn(),
    createCategory: vi.fn(),
    updateCategory: vi.fn(),
    deleteCategory: vi.fn(),
  };
});

function renderPage() {
  return renderCatalog(<CategoryPage />, { route: '/admin/categories', path: '/admin/categories' });
}

beforeEach(() => {
  vi.mocked(listCategories).mockResolvedValue(categoriesFixture);
  vi.mocked(createCategory).mockResolvedValue({ id: 4, name: '历史', description: null, bookCount: 0 });
  vi.mocked(updateCategory).mockResolvedValue({ ...categoriesFixture[0], name: '文学经典' });
  vi.mocked(deleteCategory).mockResolvedValue(undefined);
});

describe('AC-CAT-01 / AC-UI-01 CategoryPage 状态与列表', () => {
  it('有数据：显示名称、描述与 bookCount', async () => {
    renderPage();
    expect(await screen.findByText('文学')).toBeInTheDocument();
    expect(screen.getByText('小说与散文')).toBeInTheDocument();
    expect(screen.getByText('3 本')).toBeInTheDocument();
    expect(screen.getByText('0 本')).toBeInTheDocument();
  });

  it('加载中显示加载提示', () => {
    vi.mocked(listCategories).mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText('分类加载中…')).toBeInTheDocument();
  });

  it('空态提供"新建分类"引导', async () => {
    vi.mocked(listCategories).mockResolvedValue([]);
    renderPage();
    expect(await screen.findByText('还没有分类，先创建一个吧')).toBeInTheDocument();
    expect(screen.getAllByRole('button', { name: /新建分类/ }).length).toBeGreaterThanOrEqual(2);
  });

  it('错误显示"系统繁忙"，重试后恢复', async () => {
    vi.mocked(listCategories)
      .mockRejectedValueOnce(new ApiError(503, 'DB_BUSY', 'busy'))
      .mockResolvedValue(categoriesFixture);
    renderPage();
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /重\s*试/ }));
    expect(await screen.findByText('文学')).toBeInTheDocument();
  });
});

describe('AC-CAT-01 / AC-UI-02 分类新建与编辑', () => {
  it('AC-UI-02 名称必填校验：空提交不调用接口', async () => {
    renderPage();
    await screen.findByText('文学');
    await userEvent.click(screen.getAllByRole('button', { name: /新建分类/ })[0]);
    await userEvent.click(await screen.findByRole('button', { name: /保\s*存/ }));
    expect(await screen.findByText('请输入分类名称')).toBeInTheDocument();
    expect(vi.mocked(createCategory)).not.toHaveBeenCalled();
  });

  it('新建成功后关闭弹窗并提示', async () => {
    renderPage();
    await screen.findByText('文学');
    await userEvent.click(screen.getAllByRole('button', { name: /新建分类/ })[0]);
    await userEvent.type(await screen.findByLabelText('分类名称'), '历史');
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => {
      expect(vi.mocked(createCategory)).toHaveBeenCalledWith({ name: '历史', description: null });
    });
    expect(await screen.findByText('分类「历史」已创建')).toBeInTheDocument();
  });

  it('AC-CAT-01 名称重复 409 映射到名称字段', async () => {
    vi.mocked(createCategory).mockRejectedValue(
      new ApiError(409, 'CATEGORY_NAME_DUPLICATE', 'dup'),
    );
    renderPage();
    await screen.findByText('文学');
    await userEvent.click(screen.getAllByRole('button', { name: /新建分类/ })[0]);
    await userEvent.type(await screen.findByLabelText('分类名称'), '文学');
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    expect(await screen.findByText('分类名称已存在')).toBeInTheDocument();
  });

  it('编辑回填并调用更新', async () => {
    renderPage();
    await screen.findByText('文学');
    await userEvent.click(screen.getAllByRole('button', { name: /编\s*辑/ })[0]);
    const nameInput = await screen.findByLabelText('分类名称');
    expect(nameInput).toHaveValue('文学');
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, '文学经典');
    await userEvent.click(screen.getByRole('button', { name: /保\s*存/ }));
    await waitFor(() => {
      expect(vi.mocked(updateCategory)).toHaveBeenCalledWith(2, {
        name: '文学经典',
        description: '小说与散文',
      });
    });
  });
});

describe('AC-CAT-01 / AC-UI-02 分类删除', () => {
  it('删除需二次确认，确认后调用接口', async () => {
    renderPage();
    await screen.findByText('文学');
    await userEvent.click(screen.getAllByRole('button', { name: /删\s*除$/ })[0]);
    expect(vi.mocked(deleteCategory)).not.toHaveBeenCalled();
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));
    await waitFor(() => {
      expect(vi.mocked(deleteCategory)).toHaveBeenCalledWith(2);
    });
    expect(await screen.findByText('分类已删除')).toBeInTheDocument();
  });

  it('AC-CAT-01 删除仍被图书引用的分类：409 CATEGORY_IN_USE 中文文案', async () => {
    vi.mocked(deleteCategory).mockRejectedValue(new ApiError(409, 'CATEGORY_IN_USE', 'in use'));
    renderPage();
    await screen.findByText('文学');
    await userEvent.click(screen.getAllByRole('button', { name: /删\s*除$/ })[0]);
    await userEvent.click(await screen.findByRole('button', { name: /确认删除/ }));
    expect(await screen.findByText('该分类下仍有图书，不能删除')).toBeInTheDocument();
  });
});