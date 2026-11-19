// AC-RDR-06、AC-UI-01：我的资料页（GET /api/readers/me）四种状态与字段展示。
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ApiError } from '../../../api/client';
import { getMyReaderProfile } from '../api';
import MyProfilePage from '../pages/MyProfilePage';
import { reader1, reader2, renderPage } from './helpers';

vi.mock('../api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api')>();
  return { ...actual, getMyReaderProfile: vi.fn() };
});

const mockMe = vi.mocked(getMyReaderProfile);

beforeEach(() => {
  mockMe.mockResolvedValue(reader1);
});

describe('AC-RDR-06 我的资料', () => {
  it('展示本人卡号、姓名、联系方式、在借数与逾期数', async () => {
    renderPage(<MyProfilePage />);
    expect(await screen.findByText('R000001')).toBeInTheDocument();
    expect(screen.getByText('张三')).toBeInTheDocument();
    expect(screen.getByText('13800000000')).toBeInTheDocument();
    expect(screen.getByText('zhang@example.com')).toBeInTheDocument();
    expect(screen.getByText('在借数量')).toBeInTheDocument();
    expect(screen.getByText('2')).toBeInTheDocument();
    expect(screen.getByText('逾期数量')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument();
    expect(mockMe).toHaveBeenCalledTimes(1);
  });

  it('电话/邮箱为空时显示"未填写"', async () => {
    mockMe.mockResolvedValue(reader2);
    renderPage(<MyProfilePage />);
    expect(await screen.findByText('R000002')).toBeInTheDocument();
    expect(screen.getAllByText('未填写')).toHaveLength(2);
  });
});

describe('AC-UI-01 MyProfilePage 页面状态', () => {
  it('首次加载显示加载态', () => {
    mockMe.mockReturnValue(new Promise(() => {}));
    renderPage(<MyProfilePage />);
    expect(screen.getByText('加载中…')).toBeInTheDocument();
  });

  it('错误态显示中文文案并可重试', async () => {
    mockMe.mockRejectedValue(new ApiError(500, 'INTERNAL_ERROR', 'boom'));
    const user = userEvent.setup();
    renderPage(<MyProfilePage />);
    expect(await screen.findByText('系统内部错误，请稍后再试')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /重\s*试/ }));
    await waitFor(() => expect(mockMe).toHaveBeenCalledTimes(2));
  });

  it('404 READER_PROFILE_NOT_FOUND 显示档案缺失提示', async () => {
    mockMe.mockRejectedValue(new ApiError(404, 'READER_PROFILE_NOT_FOUND', 'missing'));
    renderPage(<MyProfilePage />);
    expect(
      await screen.findByText('未找到您的读者档案，请联系管理员'),
    ).toBeInTheDocument();
  });

  it('503 DB_BUSY 显示"系统繁忙"', async () => {
    mockMe.mockRejectedValue(new ApiError(503, 'DB_BUSY', 'busy'));
    renderPage(<MyProfilePage />);
    expect(await screen.findByText('系统繁忙，请稍后再试')).toBeInTheDocument();
  });
});