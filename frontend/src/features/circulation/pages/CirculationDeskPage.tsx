// 流通柜台（AC-LOAN-04/05 UI、AC-UI-01/03/04，设计 10.3 节）：
// - 借书 / 还书两个 Tab；借书输入读者卡号 + 副本条码，回车即提交；
//   成功后清空条码输入框并保持焦点，方便连续扫码；下方显示该读者当前在借列表，可在列表中续借。
// - 只输入卡号回车（或点「查在借」）可查看该读者在借列表，不发起借书。
// - 还书 Tab 输入条码提交，显示归还结果与逾期天数。
// - 借 / 还 / 续借的 409 等业务错误用 errors.ts 中文文案显示在结果区（10.2 节）；
//   401 / 403 由全局 SessionGuard / RequireRole 处理，本页不清除登录态。
import { useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  App as AntdApp,
  Button,
  Input,
  Space,
  Table,
  Tabs,
  Tag,
  Tooltip,
  Typography,
  type InputRef,
  type TableProps,
} from 'antd';
import { BookOutlined, RollbackOutlined, SearchOutlined } from '@ant-design/icons';
import type { BorrowInput, Loan, ReturnByBarcodeInput } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import {
  borrowLoan,
  circulationKeys,
  findReaderByCardNo,
  listLoans,
  renewLoan,
  returnLoanByBarcode,
} from '../api';
import { loanErrorText } from '../loanErrorText';
import { catalogKeys } from '../../catalog/api';
import { DeskResultCard, type DeskResult } from '../components/DeskResultCard';
import { LoanStatusTag } from '../components/LoanStatusTag';
import '../circulation.css';

interface DeskReader {
  id: number;
  cardNo: string;
  name: string;
}

function BorrowTab() {
  const { message } = AntdApp.useApp();
  const queryClient = useQueryClient();
  const [cardNo, setCardNo] = useState('');
  const [barcode, setBarcode] = useState('');
  const [reader, setReader] = useState<DeskReader | null>(null);
  const [result, setResult] = useState<DeskResult | null>(null);
  const [inputError, setInputError] = useState<string | null>(null);
  const barcodeRef = useRef<InputRef>(null);

  const invalidateLoans = () =>
    queryClient.invalidateQueries({ queryKey: circulationKeys.all });

  const borrowMutation = useMutation({
    mutationFn: (input: BorrowInput) => borrowLoan(input),
    onSuccess: (loan) => {
      setResult({ ok: true, kind: 'borrow', loan });
      setReader(loan.reader);
      setBarcode('');
      barcodeRef.current?.focus();
      void invalidateLoans();
      // I-05 / AC-BOOK-03：借出后可借数量变化，失效检索结果与图书详情缓存（staleTime 30s 内不重取会显示旧值）
      void queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
    onError: (error) => setResult({ ok: false, text: loanErrorText(error) }),
  });

  const lookupMutation = useMutation({
    mutationFn: (card: string) => findReaderByCardNo(card),
    onSuccess: (found) => {
      setReader(found);
      setResult(null);
    },
    onError: (error) => setResult({ ok: false, text: loanErrorText(error) }),
  });

  const renewMutation = useMutation({
    mutationFn: (loanId: number) => renewLoan(loanId),
    onSuccess: (loan) => {
      setResult({ ok: true, kind: 'renew', loan });
      message.success(`续借成功：《${loan.book.title}》应还日顺延至 ${loan.dueDate}`);
      void invalidateLoans();
    },
    onError: (error) => {
      setResult({ ok: false, text: loanErrorText(error) });
      // 服务端 renewable 只是提示，失败后刷新列表拿最新状态
      void invalidateLoans();
    },
  });

  const loansQuery = useQuery({
    queryKey: circulationKeys.readerLoans(reader?.id ?? 0),
    queryFn: () =>
      listLoans({
        readerId: reader?.id ?? 0,
        status: 'CURRENT',
        page: 0,
        size: 20,
        sort: 'dueDate,asc',
      }),
    enabled: reader !== null,
  });

  const submitting = borrowMutation.isPending || lookupMutation.isPending;

  const submitBorrow = () => {
    if (submitting) return; // 提交中禁止重复触发（10.2 节）
    const card = cardNo.trim();
    const code = barcode.trim();
    if (!card) {
      setInputError('请输入读者卡号');
      return;
    }
    setInputError(null);
    if (!code) {
      // 只有卡号：查看该读者在借列表，不借书
      lookupMutation.mutate(card);
      return;
    }
    borrowMutation.mutate({ readerCardNo: card, copyBarcode: code });
  };

  const lookupOnly = () => {
    if (submitting) return;
    const card = cardNo.trim();
    if (!card) {
      setInputError('请输入读者卡号');
      return;
    }
    setInputError(null);
    lookupMutation.mutate(card);
  };

  const overdueCount =
    loansQuery.data?.items.filter((loan) => loan.status === 'OVERDUE').length ?? 0;

  const columns: TableProps<Loan>['columns'] = [
    {
      title: '书名',
      key: 'title',
      render: (_, loan) => (
        <div className="circ-book-cell">
          <span style={{ fontWeight: 600 }}>{loan.book.title}</span>
          <span className="circ-book-sub">{loan.book.author}</span>
        </div>
      ),
    },
    {
      title: '条码',
      key: 'barcode',
      render: (_, loan) => <span className="circ-barcode">{loan.copy.barcode}</span>,
    },
    { title: '借阅日', dataIndex: 'borrowDate', key: 'borrowDate' },
    {
      title: '应还日',
      dataIndex: 'dueDate',
      key: 'dueDate',
      render: (value: string, loan) => (
        <span className={loan.status === 'OVERDUE' ? 'circ-due-overdue' : undefined}>{value}</span>
      ),
    },
    {
      title: '状态',
      key: 'status',
      render: (_, loan) => <LoanStatusTag loan={loan} />,
    },
    {
      title: '续借',
      key: 'renewCount',
      render: (_, loan) => `${loan.renewCount}/${loan.maxRenewals}`,
    },
    {
      title: '操作',
      key: 'action',
      render: (_, loan) => {
        const button = (
          <Button
            size="small"
            disabled={!loan.renewable || renewMutation.isPending}
            loading={renewMutation.isPending && renewMutation.variables === loan.id}
            onClick={() => renewMutation.mutate(loan.id)}
          >
            续借
          </Button>
        );
        return loan.renewable ? (
          button
        ) : (
          <Tooltip title="当前不可续借（已逾期、次数用尽或读者状态受限）">{button}</Tooltip>
        );
      },
    },
  ];

  return (
    <div>
      <div className="circ-scan-form">
        <div className="circ-scan-field">
          <label htmlFor="circ-card-no">读者卡号</label>
          <Input
            id="circ-card-no"
            className="circ-scan-input"
            placeholder="如 R000001"
            value={cardNo}
            onChange={(event) => setCardNo(event.target.value)}
            onPressEnter={submitBorrow}
            allowClear
          />
        </div>
        <div className="circ-scan-field">
          <label htmlFor="circ-borrow-barcode">副本条码</label>
          <Input
            id="circ-borrow-barcode"
            ref={barcodeRef}
            className="circ-scan-input"
            placeholder="如 C00000001"
            value={barcode}
            onChange={(event) => setBarcode(event.target.value)}
            onPressEnter={submitBorrow}
            allowClear
          />
        </div>
        <div className="circ-scan-field">
          <label aria-hidden="true">&nbsp;</label>
          <Space>
            <Button
              type="primary"
              icon={<BookOutlined />}
              loading={borrowMutation.isPending}
              disabled={submitting}
              onClick={submitBorrow}
            >
              借书
            </Button>
            <Button
              icon={<SearchOutlined />}
              loading={lookupMutation.isPending}
              disabled={submitting}
              onClick={lookupOnly}
            >
              查在借
            </Button>
          </Space>
        </div>
      </div>
      {inputError && (
        <Typography.Text type="danger" style={{ display: 'block', marginTop: 8 }}>
          {inputError}
        </Typography.Text>
      )}
      <div className="circ-scan-hint">
        扫码或输入后回车即可借书；只输入卡号回车可查看该读者在借列表。借出成功后条码框自动清空并保持焦点。
      </div>

      {result && <DeskResultCard result={result} />}

      {reader && (
        <section className="circ-reader-panel" aria-label="读者在借列表">
          <div className="circ-reader-summary">
            <Typography.Text strong style={{ fontSize: 15 }}>
              {reader.name}
            </Typography.Text>
            <span className="circ-barcode">{reader.cardNo}</span>
            {loansQuery.data && <Tag color="processing">在借 {loansQuery.data.totalItems} 册</Tag>}
            {overdueCount > 0 && <Tag color="error">其中逾期 {overdueCount} 册</Tag>}
          </div>
          {loansQuery.isPending ? (
            <PageLoading tip="正在加载在借列表…" />
          ) : loansQuery.isError ? (
            <PageError error={loansQuery.error} onRetry={() => void loansQuery.refetch()} />
          ) : loansQuery.data.items.length === 0 ? (
            <EmptyState description="该读者当前没有在借图书" />
          ) : (
            <Table<Loan>
              rowKey="id"
              size="small"
              columns={columns}
              dataSource={loansQuery.data.items}
              pagination={false}
              loading={loansQuery.isFetching}
              rowClassName={(loan) => (loan.status === 'OVERDUE' ? 'circ-row-overdue' : '')}
            />
          )}
        </section>
      )}
    </div>
  );
}

function ReturnTab() {
  const queryClient = useQueryClient();
  const [barcode, setBarcode] = useState('');
  const [result, setResult] = useState<DeskResult | null>(null);
  const [inputError, setInputError] = useState<string | null>(null);
  const barcodeRef = useRef<InputRef>(null);

  const returnMutation = useMutation({
    mutationFn: (input: ReturnByBarcodeInput) => returnLoanByBarcode(input),
    onSuccess: (loan) => {
      setResult({ ok: true, kind: 'return', loan });
      setBarcode('');
      barcodeRef.current?.focus();
      void queryClient.invalidateQueries({ queryKey: circulationKeys.all });
      // I-05 / AC-BOOK-03：归还后可借数量变化，失效检索结果与图书详情缓存
      void queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
    onError: (error) => setResult({ ok: false, text: loanErrorText(error) }),
  });

  const submitReturn = () => {
    if (returnMutation.isPending) return; // 提交中禁止重复触发（10.2 节）
    const code = barcode.trim();
    if (!code) {
      setInputError('请输入副本条码');
      return;
    }
    setInputError(null);
    returnMutation.mutate({ copyBarcode: code });
  };

  return (
    <div>
      <div className="circ-scan-form">
        <div className="circ-scan-field">
          <label htmlFor="circ-return-barcode">副本条码</label>
          <Input
            id="circ-return-barcode"
            ref={barcodeRef}
            className="circ-scan-input"
            placeholder="如 C00000001"
            value={barcode}
            onChange={(event) => setBarcode(event.target.value)}
            onPressEnter={submitReturn}
            allowClear
          />
        </div>
        <div className="circ-scan-field">
          <label aria-hidden="true">&nbsp;</label>
          <Button
            type="primary"
            icon={<RollbackOutlined />}
            loading={returnMutation.isPending}
            disabled={returnMutation.isPending}
            onClick={submitReturn}
          >
            还书
          </Button>
        </div>
      </div>
      {inputError && (
        <Typography.Text type="danger" style={{ display: 'block', marginTop: 8 }}>
          {inputError}
        </Typography.Text>
      )}
      <div className="circ-scan-hint">
        输入或扫描副本条码后回车即可还书；逾期归还照常完成，结果中会显示逾期天数。
      </div>

      {result && <DeskResultCard result={result} />}
    </div>
  );
}

export default function CirculationDeskPage() {
  return (
    <section>
      <div className="circ-page-header">
        <Typography.Title level={3} style={{ margin: 0 }}>
          流通柜台
        </Typography.Title>
        <Typography.Text type="secondary">借书 · 还书 · 续借</Typography.Text>
      </div>
      <Tabs
        className="circ-desk-tabs"
        defaultActiveKey="borrow"
        destroyOnHidden
        items={[
          { key: 'borrow', label: '借书', children: <BorrowTab /> },
          { key: 'return', label: '还书', children: <ReturnTab /> },
        ]}
      />
    </section>
  );
}