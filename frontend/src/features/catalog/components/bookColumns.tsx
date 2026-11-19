// 图书表格的公共列（检索页与管理员列表共用），排序白名单见 api.ts BOOK_SORT_FIELDS。
import { Link } from 'react-router';
import { Space, Tag, Typography } from 'antd';
import type { TableProps } from 'antd';
import type { BookSummary } from '../../../api/types';
import { AvailabilityBadge } from './AvailabilityBadge';
import { BookCover } from './BookCover';

export type SortOrder = 'ascend' | 'descend' | null;

/** 把 URL/状态中的 sort（"field,dir"）换算成某列的受控 sortOrder。 */
export function sortOrderOf(sort: string, field: string): SortOrder {
  const [sortField, dir] = sort.split(',');
  if (sortField !== field) return null;
  return dir === 'desc' ? 'descend' : 'ascend';
}

export function bookBaseColumns(sort: string): TableProps<BookSummary>['columns'] {
  return [
    {
      title: '书名',
      dataIndex: 'title',
      key: 'title',
      sorter: true,
      sortOrder: sortOrderOf(sort, 'title'),
      render: (_, book) => (
        <Space size={12} align="center">
          <BookCover title={book.title} author={book.author} size="sm" />
          <Link to={`/books/${book.id}`} style={{ fontWeight: 600 }}>
            {book.title}
          </Link>
        </Space>
      ),
    },
    {
      title: '作者',
      dataIndex: 'author',
      key: 'author',
      sorter: true,
      sortOrder: sortOrderOf(sort, 'author'),
      width: 160,
      ellipsis: true,
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      width: 130,
      render: (_, book) =>
        book.category ? <Tag color="green">{book.category.name}</Tag> : <Typography.Text type="secondary">未分类</Typography.Text>,
    },
    {
      title: 'ISBN',
      dataIndex: 'isbn',
      key: 'isbn',
      width: 160,
      render: (isbn: string) => (
        <Typography.Text style={{ fontVariantNumeric: 'tabular-nums' }} type="secondary">
          {isbn}
        </Typography.Text>
      ),
    },
    {
      title: '出版年',
      dataIndex: 'publishYear',
      key: 'publishYear',
      sorter: true,
      sortOrder: sortOrderOf(sort, 'publishYear'),
      width: 100,
      render: (year: number | null) => year ?? '—',
    },
    {
      title: '可借 / 馆藏',
      key: 'availability',
      width: 150,
      render: (_, book) => (
        <AvailabilityBadge
          availableCopies={book.availableCopies}
          totalCopies={book.totalCopies}
        />
      ),
    },
  ];
}