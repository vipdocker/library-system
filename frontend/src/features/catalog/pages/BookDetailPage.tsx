// 图书详情页（AC-SRCH-02 / AC-BOOK-03 / AC-UI-01）。
// 管理员与读者都可查看书目信息与可借数量；副本列表仅管理员在编辑页可见（读者不请求 /copies）。
import { Button, Descriptions, Flex, Progress, Space, Tag, Typography } from 'antd';
import type { DescriptionsProps } from 'antd';
import { ArrowLeftOutlined, EditOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../../../api/client';
import { errorMessage } from '../../../api/errors';
import { useAuth } from '../../../auth/AuthContext';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { catalogKeys, getBook } from '../api';
import { BookCover } from '../components/BookCover';

export default function BookDetailPage() {
  const { id } = useParams();
  const bookId = Number(id);
  const navigate = useNavigate();
  const { user } = useAuth();
  const isAdmin = user?.role === 'ADMIN';

  const bookQuery = useQuery({
    queryKey: catalogKeys.book(bookId),
    queryFn: () => getBook(bookId),
    enabled: Number.isInteger(bookId) && bookId > 0,
  });

  const backButton = (
    <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)}>
      返回
    </Button>
  );

  if (!Number.isInteger(bookId) || bookId <= 0) {
    return (
      <EmptyState
        description={errorMessage('BOOK_NOT_FOUND')}
        action={<Link to="/books"><Button type="primary">回到图书检索</Button></Link>}
      />
    );
  }

  if (bookQuery.isPending) return <PageLoading tip="图书详情加载中…" />;

  if (bookQuery.isError) {
    const error = bookQuery.error;
    if (error instanceof ApiError && error.status === 404) {
      return (
        <EmptyState
          description={errorMessage('BOOK_NOT_FOUND')}
          action={<Link to="/books"><Button type="primary">回到图书检索</Button></Link>}
        />
      );
    }
    return (
      <Space orientation="vertical" size={16} style={{ width: '100%' }}>
        <PageError error={error} onRetry={() => bookQuery.refetch()} />
        {backButton}
      </Space>
    );
  }

  const book = bookQuery.data;
  const percent =
    book.totalCopies > 0 ? Math.round((book.availableCopies / book.totalCopies) * 100) : 0;

  const items: DescriptionsProps['items'] = [
    { key: 'isbn', label: 'ISBN', children: <Typography.Text copyable>{book.isbn}</Typography.Text> },
    { key: 'author', label: '作者', children: book.author },
    { key: 'publisher', label: '出版社', children: book.publisher ?? '—' },
    { key: 'publishYear', label: '出版年', children: book.publishYear ?? '—' },
    {
      key: 'category',
      label: '分类',
      children: book.category ? <Tag>{book.category.name}</Tag> : '未分类',
    },
    {
      key: 'createdAt',
      label: '入库时间',
      children: dayjs(book.createdAt).format('YYYY-MM-DD HH:mm'),
    },
    {
      key: 'updatedAt',
      label: '最近更新',
      children: dayjs(book.updatedAt).format('YYYY-MM-DD HH:mm'),
    },
  ];

  return (
    <section aria-label="图书详情">
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 20 }} wrap>
        {backButton}
        {isAdmin && (
          <Link to={`/admin/books/${book.id}/edit`}>
            <Button type="primary" icon={<EditOutlined />}>
              编辑图书与副本
            </Button>
          </Link>
        )}
      </Space>

      <Flex
        gap={28}
        wrap
        style={{
          background: 'var(--linear-surface-2)',
          border: '1px solid var(--linear-hairline)',
          borderRadius: 'var(--linear-radius-lg)',
          padding: 'clamp(16px, 4vw, 28px)',
          marginBottom: 24,
        }}
      >
        <BookCover title={book.title} author={book.author} size="lg" />
        <div style={{ flex: '1 1 240px', minWidth: 0 }}>
          <Typography.Title level={2} style={{ marginTop: 0, marginBottom: 4 }}>
            {book.title}
          </Typography.Title>
          <Typography.Paragraph type="secondary" style={{ fontSize: 15 }}>
            {book.author}
            {book.publisher ? ` · ${book.publisher}` : ''}
            {book.publishYear ? ` · ${book.publishYear}` : ''}
          </Typography.Paragraph>
          <Space size={24} align="center" wrap>
            <div style={{ minWidth: 0, flex: '1 1 220px', maxWidth: 280 }}>
              <Space size={8} align="baseline">
                <Typography.Text strong style={{ fontSize: 22, color: 'var(--linear-ink)' }}>
                  {book.availableCopies}
                </Typography.Text>
                <Typography.Text type="secondary">册可借 / 馆藏 {book.totalCopies} 册</Typography.Text>
              </Space>
              <Progress
                percent={percent}
                showInfo={false}
                strokeColor="var(--linear-ink-subtle)"
                railColor="var(--linear-surface-3)"
                size="small"
              />
            </div>
            <Tag color={book.availableCopies > 0 ? 'success' : 'default'}>
              {book.availableCopies > 0 ? '现在可借' : '暂无可借副本'}
            </Tag>
          </Space>
        </div>
      </Flex>

      <Descriptions title="书目信息" bordered column={{ xs: 1, sm: 2, md: 3 }} items={items} />

      <div style={{ marginTop: 24 }}>
        <Typography.Title level={5}>内容简介</Typography.Title>
        <Typography.Paragraph
          type={book.description ? undefined : 'secondary'}
          style={{ whiteSpace: 'pre-wrap', maxWidth: 760 }}
        >
          {book.description ?? '暂无简介'}
        </Typography.Paragraph>
      </div>
    </section>
  );
}