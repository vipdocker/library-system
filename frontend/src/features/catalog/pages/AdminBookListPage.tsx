// 管理员图书列表（AC-BOOK-03 / AC-UI-01 / AC-UI-02）。
// 检索 + 分类筛选 + 分页排序（白名单）；新建入口；删除需 Popconfirm 二次确认，
// 409（有在借/借阅历史）用 errors.ts 中文文案提示，数据不变。
import { useState } from 'react';
import { App, Button, Input, Popconfirm, Select, Space, Table, Typography } from 'antd';
import type { TableProps } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined, SearchOutlined } from '@ant-design/icons';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate } from 'react-router';
import type { BookSummary } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { tablePagination } from '../../../components/pagination';
import {
  DEFAULT_BOOK_QUERY,
  DEFAULT_BOOK_SORT,
  catalogKeys,
  deleteBook,
  listCategories,
  searchBooks,
  type BookQuery,
} from '../api';
import { bookBaseColumns } from '../components/bookColumns';
import { apiErrorMessage } from '../formErrors';

export default function AdminBookListPage() {
  const { message } = App.useApp();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const [query, setQuery] = useState<BookQuery>(DEFAULT_BOOK_QUERY);
  const [keyword, setKeyword] = useState('');

  const categoriesQuery = useQuery({
    queryKey: catalogKeys.categories(),
    queryFn: listCategories,
  });

  const booksQuery = useQuery({
    queryKey: catalogKeys.books(query),
    queryFn: () => searchBooks(query),
    placeholderData: keepPreviousData,
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteBook(id),
    onSuccess: () => {
      message.success('图书已删除，其副本一并删除');
      queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
    onError: (error) => message.error(apiErrorMessage(error)),
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
    setQuery((prev) => ({
      ...prev,
      sort: nextSort,
      page: sortChanged ? 0 : (pagination.current ?? 1) - 1,
      size: pagination.pageSize ?? prev.size,
    }));
  };

  const columns: TableProps<BookSummary>['columns'] = [
    ...(bookBaseColumns(query.sort) ?? []),
    {
      title: '操作',
      key: 'actions',
      width: 170,
      render: (_, book) => (
        <Space size={8}>
          <Link to={`/admin/books/${book.id}/edit`}>
            <Button size="small" icon={<EditOutlined />}>
              编辑
            </Button>
          </Link>
          <Popconfirm
            title="删除该图书？"
            description="将同时删除其所有副本；有借阅历史的图书无法删除。"
            okText="确认删除"
            okButtonProps={{
              danger: true,
              loading: deleteMutation.isPending && deleteMutation.variables === book.id,
            }}
            cancelText="取消"
            onConfirm={() => deleteMutation.mutate(book.id)}
          >
            <Button size="small" danger icon={<DeleteOutlined />} disabled={deleteMutation.isPending}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  const hasFilters = query.q !== '' || query.categoryId !== undefined;

  return (
    <section aria-label="图书管理">
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }} wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          图书管理
        </Typography.Title>
        <Link to="/admin/books/new">
          <Button type="primary" icon={<PlusOutlined />}>
            新建图书
          </Button>
        </Link>
      </Space>

      <Space size={12} wrap style={{ marginBottom: 16 }}>
        <Input.Search
          aria-label="检索关键词"
          placeholder="按书名、作者或 ISBN 检索…"
          allowClear
          maxLength={100}
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onSearch={(value) => setQuery((prev) => ({ ...prev, q: value, page: 0 }))}
          enterButton={
            <Button icon={<SearchOutlined />}>检索</Button>
          }
          style={{ width: 360 }}
        />
        <Select<number>
          aria-label="分类筛选"
          placeholder="全部分类"
          allowClear
          value={query.categoryId}
          onChange={(value) => setQuery((prev) => ({ ...prev, categoryId: value, page: 0 }))}
          options={categoriesQuery.data?.map((c) => ({
            value: c.id,
            label: `${c.name}（${c.bookCount}）`,
          }))}
          loading={categoriesQuery.isPending}
          style={{ width: 200 }}
        />
      </Space>

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
          scroll={{ x: 1080 }}
          pagination={tablePagination(booksQuery.data, (pageIndex, pageSize) =>
            setQuery((prev) => ({ ...prev, page: pageIndex, size: pageSize })),
          )}
          locale={{
            emptyText: hasFilters ? (
              <EmptyState
                description="没有找到匹配的图书"
                action={
                  <Button
                    onClick={() => {
                      setKeyword('');
                      setQuery(DEFAULT_BOOK_QUERY);
                    }}
                  >
                    清空条件
                  </Button>
                }
              />
            ) : (
              <EmptyState
                description="馆藏中还没有图书"
                action={
                  <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/admin/books/new')}>
                    新建图书
                  </Button>
                }
              />
            ),
          }}
        />
      )}
    </section>
  );
}