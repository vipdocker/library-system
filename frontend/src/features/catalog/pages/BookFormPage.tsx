// 图书表单页（AC-BOOK-01 / AC-UI-02）：/admin/books/new 新建；/admin/books/:id/edit 编辑。
// 编辑页内含副本管理（追加、改状态、删除，见 CopiesPanel）。
// 前端校验与第 8.4 节契约一致（含 D4③ ISBN 严格校验位）；服务端 fieldErrors 映射到对应表单项；
// 提交中按钮 loading 并禁用，防止重复提交。
import { useState } from 'react';
import {
  Alert,
  App,
  Button,
  Divider,
  Flex,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Typography,
} from 'antd';
import { ArrowLeftOutlined, SaveOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useNavigate, useParams } from 'react-router';
import { ApiError } from '../../../api/client';
import { errorMessage } from '../../../api/errors';
import type { BookInput } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { catalogKeys, createBook, getBook, listCategories, updateBook } from '../api';
import { CopiesPanel } from '../components/CopiesPanel';
import { applyServerFieldErrors } from '../formErrors';
import { isValidIsbn, normalizeIsbn, trimToNull } from '../validation';

interface BookFormValues {
  isbn: string;
  title: string;
  author: string;
  publisher?: string;
  publishYear?: number | null;
  categoryId?: number;
  description?: string;
  initialCopies?: number;
}

const FORM_FIELDS = [
  'isbn',
  'title',
  'author',
  'publisher',
  'publishYear',
  'categoryId',
  'description',
  'initialCopies',
] as const;

export default function BookFormPage() {
  const { id } = useParams();
  const isEdit = id !== undefined;
  const bookId = Number(id);
  const navigate = useNavigate();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<BookFormValues>();
  const [formError, setFormError] = useState<string | null>(null);

  const bookQuery = useQuery({
    queryKey: catalogKeys.book(bookId),
    queryFn: () => getBook(bookId),
    enabled: isEdit && Number.isInteger(bookId) && bookId > 0,
  });

  const categoriesQuery = useQuery({
    queryKey: catalogKeys.categories(),
    queryFn: listCategories,
  });

  const saveMutation = useMutation({
    mutationFn: (values: BookFormValues) => {
      const base: BookInput = {
        isbn: normalizeIsbn(values.isbn),
        title: values.title.trim(),
        author: values.author.trim(),
        publisher: trimToNull(values.publisher),
        publishYear: values.publishYear ?? null,
        categoryId: values.categoryId ?? null,
        description: trimToNull(values.description),
      };
      return isEdit
        ? updateBook(bookId, base)
        : createBook({ ...base, initialCopies: values.initialCopies ?? 1 });
    },
    onSuccess: (book) => {
      queryClient.invalidateQueries({ queryKey: catalogKeys.all });
      if (isEdit) {
        message.success('图书已保存');
      } else {
        message.success('图书已创建，可继续追加副本');
        navigate(`/admin/books/${book.id}/edit`, { replace: true });
      }
    },
    onError: (error) => {
      const { formLevelMessage } = applyServerFieldErrors(form, error, FORM_FIELDS);
      if (formLevelMessage) setFormError(formLevelMessage);
    },
  });

  const onFinish = (values: BookFormValues) => {
    setFormError(null);
    saveMutation.mutate(values);
  };

  if (isEdit && (!Number.isInteger(bookId) || bookId <= 0)) {
    return (
      <EmptyState
        description={errorMessage('BOOK_NOT_FOUND')}
        action={<Link to="/admin/books"><Button type="primary">返回图书管理</Button></Link>}
      />
    );
  }

  if (isEdit && bookQuery.isPending) return <PageLoading tip="图书加载中…" />;

  if (isEdit && bookQuery.isError) {
    const error = bookQuery.error;
    if (error instanceof ApiError && error.status === 404) {
      return (
        <EmptyState
          description={errorMessage('BOOK_NOT_FOUND')}
          action={<Link to="/admin/books"><Button type="primary">返回图书管理</Button></Link>}
        />
      );
    }
    return <PageError error={error} onRetry={() => bookQuery.refetch()} />;
  }

  const book = isEdit ? bookQuery.data : undefined;
  const initialValues: Partial<BookFormValues> = book
    ? {
        isbn: book.isbn,
        title: book.title,
        author: book.author,
        publisher: book.publisher ?? undefined,
        publishYear: book.publishYear,
        categoryId: book.category?.id,
        description: book.description ?? undefined,
      }
    : { initialCopies: 1 };

  return (
    <section aria-label="图书表单">
      <Space style={{ marginBottom: 16 }}>
        <Link to="/admin/books">
          <Button icon={<ArrowLeftOutlined />}>返回图书管理</Button>
        </Link>
      </Space>
      <Typography.Title level={3}>
        {isEdit ? `编辑图书：${book?.title ?? ''}` : '新建图书'}
      </Typography.Title>
      <Typography.Paragraph type="secondary">
        {isEdit
          ? '修改书目信息后保存；副本的追加、状态调整与删除在下方"副本管理"中进行。'
          : '填写书目信息并设置初始副本数量；创建后可在编辑页继续追加副本。'}
      </Typography.Paragraph>

      {formError && (
        <Alert
          type="error"
          showIcon
          closable
          title="提交失败"
          description={formError}
          onClose={() => setFormError(null)}
          style={{ marginBottom: 16, maxWidth: 720 }}
        />
      )}

      <Form<BookFormValues>
        form={form}
        layout="vertical"
        initialValues={initialValues}
        onFinish={onFinish}
        style={{ maxWidth: 720 }}
        requiredMark
      >
        <Flex gap={16} wrap>
          <Form.Item
            name="isbn"
            label="ISBN"
            style={{ flex: '1 1 260px' }}
            extra="支持 10 位或 13 位，可含连字符；保存时自动规范化并校验校验位"
            rules={[
              { required: true, whitespace: true, message: '请输入 ISBN' },
              {
                validator: (_, value: string) =>
                  !value || isValidIsbn(value)
                    ? Promise.resolve()
                    : Promise.reject(new Error('ISBN 格式或校验位不正确（支持 10/13 位）')),
              },
            ]}
          >
            <Input placeholder="如 978-0-306-40615-7" maxLength={20} allowClear />
          </Form.Item>
          <Form.Item
            name="publishYear"
            label="出版年"
            style={{ flex: '0 0 160px' }}
            rules={[
              {
                type: 'number',
                min: 1000,
                max: 9999,
                message: '出版年须在 1000–9999 之间',
              },
            ]}
          >
            <InputNumber min={1000} max={9999} precision={0} style={{ width: '100%' }} placeholder="如 2021" />
          </Form.Item>
        </Flex>

        <Form.Item
          name="title"
          label="书名"
          rules={[
            { required: true, whitespace: true, message: '请输入书名' },
            { max: 200, message: '书名不能超过 200 字' },
          ]}
        >
          <Input placeholder="书名（必填，≤200 字）" maxLength={200} allowClear />
        </Form.Item>

        <Flex gap={16} wrap>
          <Form.Item
            name="author"
            label="作者"
            style={{ flex: '1 1 260px' }}
            rules={[
              { required: true, whitespace: true, message: '请输入作者' },
              { max: 100, message: '作者不能超过 100 字' },
            ]}
          >
            <Input placeholder="作者（必填，≤100 字）" maxLength={100} allowClear />
          </Form.Item>
          <Form.Item
            name="publisher"
            label="出版社"
            style={{ flex: '1 1 260px' }}
            rules={[{ max: 100, message: '出版社不能超过 100 字' }]}
          >
            <Input placeholder="出版社（可选）" maxLength={100} allowClear />
          </Form.Item>
        </Flex>

        <Flex gap={16} wrap>
          <Form.Item name="categoryId" label="分类" style={{ flex: '1 1 260px' }}>
            <Select<number>
              placeholder="选择分类（可选）"
              allowClear
              options={categoriesQuery.data?.map((c) => ({ value: c.id, label: c.name }))}
              loading={categoriesQuery.isPending}
            />
          </Form.Item>
          {!isEdit && (
            <Form.Item
              name="initialCopies"
              label="初始副本数量"
              style={{ flex: '0 0 200px' }}
              extra="创建时自动生成，0–50 册"
              rules={[
                {
                  type: 'number',
                  min: 0,
                  max: 50,
                  message: '初始副本数量须在 0–50 之间',
                },
              ]}
            >
              <InputNumber min={0} max={50} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          )}
        </Flex>

        <Form.Item
          name="description"
          label="内容简介"
          rules={[{ max: 2000, message: '简介不能超过 2000 字' }]}
        >
          <Input.TextArea rows={4} maxLength={2000} showCount placeholder="内容简介（可选，≤2000 字）" />
        </Form.Item>

        <Form.Item>
          <Space size={12}>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SaveOutlined />}
              loading={saveMutation.isPending}
              disabled={saveMutation.isPending}
            >
              {isEdit ? '保存修改' : '创建图书'}
            </Button>
            <Button onClick={() => navigate('/admin/books')} disabled={saveMutation.isPending}>
              取消
            </Button>
          </Space>
        </Form.Item>
      </Form>

      {isEdit && book && (
        <>
          <Divider />
          <div style={{ maxWidth: 900 }}>
            <CopiesPanel bookId={book.id} />
          </div>
        </>
      )}
    </section>
  );
}