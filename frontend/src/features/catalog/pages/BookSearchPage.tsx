// 图书检索页（AC-SRCH-02 / AC-BOOK-03 / AC-UI-01）。
// 检索条件（q、field、categoryId、availableOnly、page、size、sort）全部写入 URL query，刷新后保留。
// 状态规范（设计 10.2）：首次加载 Spin；翻页/筛选保留旧数据并显示表格 loading；
// 空态 Empty 引导；错误 Alert 中文文案 + 重试（refetch）；401/403 由全局 SessionGuard / RequireRole 处理。
import { useEffect, useMemo, useState } from 'react';
import { Button, Checkbox, Input, Select, Space, Table, Typography } from 'antd';
import type { TableProps } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { keepPreviousData, useQuery } from '@tanstack/react-query';
import { useSearchParams } from 'react-router';
import type { BookSearchField, BookSummary } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { tablePagination } from '../../../components/pagination';
import {
  DEFAULT_BOOK_SORT,
  catalogKeys,
  listCategories,
  searchBooks,
  type BookQuery,
} from '../api';
import { bookBaseColumns } from '../components/bookColumns';
import { bookQueryToParams, parseBookQuery } from '../searchQuery';

const FIELD_OPTIONS: Array<{ value: BookSearchField; label: string }> = [
  { value: 'all', label: '全部' },
  { value: 'title', label: '书名' },
  { value: 'author', label: '作者' },
  { value: 'isbn', label: 'ISBN' },
];

export default function BookSearchPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const query = useMemo(() => parseBookQuery(searchParams), [searchParams]);

  const [keyword, setKeyword] = useState(query.q);
  const [field, setField] = useState<BookSearchField>(query.field);
  useEffect(() => {
    setKeyword(query.q);
    setField(query.field);
  }, [query.q, query.field]);

  const updateQuery = (patch: Partial<BookQuery>) => {
    setSearchParams(bookQueryToParams({ ...query, ...patch }));
  };

  const categoriesQuery = useQuery({
    queryKey: catalogKeys.categories(),
    queryFn: listCategories,
  });

  const booksQuery = useQuery({
    queryKey: catalogKeys.books(query),
    queryFn: () => searchBooks(query),
    placeholderData: keepPreviousData,
  });

  const handleTableChange: TableProps<BookSummary>['onChange'] = (
    pagination,
    _filters,
    sorter,
  ) => {
    const single = Array.isArray(sorter) ? sorter[0] : sorter;
    const sortField = typeof single?.columnKey === 'string' ? single.columnKey : null;
    const nextSort =
      single?.order && sortField
        ? `${sortField},${single.order === 'descend' ? 'desc' : 'asc'}`
        : DEFAULT_BOOK_SORT;
    const sortChanged = nextSort !== query.sort;
    updateQuery({
      sort: nextSort,
      page: sortChanged ? 0 : (pagination.current ?? 1) - 1,
      size: pagination.pageSize ?? query.size,
    });
  };

  const hasFilters =
    query.q !== '' || query.categoryId !== undefined || query.availableOnly || query.field !== 'all';

  const columns = bookBaseColumns(query.sort);

  return (
    <section aria-label="图书检索">
      <Space
        style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }}
        align="baseline"
        wrap
      >
        <Typography.Title level={3} style={{ margin: 0 }}>
          图书检索
        </Typography.Title>
        {booksQuery.data && (
          <Typography.Text type="secondary">
            共找到 {booksQuery.data.totalItems} 本图书
          </Typography.Text>
        )}
      </Space>

      <div
        style={{
          background: 'var(--lib-paper-deep, #efe9dc)',
          border: '1px solid rgba(30, 107, 82, 0.14)',
          borderRadius: 10,
          padding: 16,
          marginBottom: 16,
        }}
      >
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12, alignItems: 'center' }}>
          <Select<BookSearchField>
            aria-label="检索字段"
            value={field}
            onChange={setField}
            options={FIELD_OPTIONS}
            style={{ width: 110 }}
          />
          <Input.Search
            aria-label="检索关键词"
            placeholder="输入书名、作者或 ISBN…"
            allowClear
            maxLength={100}
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
            onSearch={(value) => updateQuery({ q: value, field, page: 0 })}
            enterButton={
              <Button type="primary" icon={<SearchOutlined />}>
                检索
              </Button>
            }
            style={{ flex: '1 1 240px', maxWidth: 420 }}
          />
          <Select<number>
            aria-label="分类筛选"
            placeholder="全部分类"
            allowClear
            value={query.categoryId}
            onChange={(value) => updateQuery({ categoryId: value, page: 0 })}
            options={categoriesQuery.data?.map((c) => ({
              value: c.id,
              label: `${c.name}（${c.bookCount}）`,
            }))}
            loading={categoriesQuery.isPending}
            style={{ flex: '0 1 200px', minWidth: 150 }}
          />
          <Checkbox
            checked={query.availableOnly}
            onChange={(e) => updateQuery({ availableOnly: e.target.checked, page: 0 })}
          >
            仅显示可借
          </Checkbox>
          {hasFilters && (
            <Button onClick={() => setSearchParams(new URLSearchParams())}>重置条件</Button>
          )}
        </div>
      </div>

      {booksQuery.isPending ? (
        <PageLoading tip="图书加载中…" />
      ) : booksQuery.isError ? (
        <PageError error={booksQuery.error} onRetry={() => booksQuery.refetch()} />
      ) : (
        <Table<BookSummary>
          rowKey="id"
          columns={columns}
          dataSource={booksQuery.data.items}
          loading={booksQuery.isFetching}
          onChange={handleTableChange}
          scroll={{ x: 960 }}
          pagination={tablePagination(booksQuery.data, (pageIndex, pageSize) =>
            updateQuery({ page: pageIndex, size: pageSize }),
          )}
          locale={{
            emptyText: (
              <EmptyState
                description="没有找到匹配的图书"
                action={
                  hasFilters ? (
                    <Button onClick={() => setSearchParams(new URLSearchParams())}>
                      清空条件，查看全部图书
                    </Button>
                  ) : undefined
                }
              />
            ),
          }}
        />
      )}
    </section>
  );
}