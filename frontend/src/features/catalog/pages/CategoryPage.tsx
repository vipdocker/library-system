// 分类管理页（AC-CAT-01 / AC-UI-01 / AC-UI-02）。
// 列表含 bookCount；新建/编辑用 Modal 表单（名称 ≤50、描述 ≤200）；
// 名称重复 409 映射到名称字段；删除仍被引用的分类 409 CATEGORY_IN_USE 中文提示；删除需二次确认。
import { useState } from 'react';
import {
  Alert,
  App,
  Button,
  Form,
  Input,
  Modal,
  Popconfirm,
  Space,
  Table,
  Tag,
  Typography,
} from 'antd';
import type { TableProps } from 'antd';
import { DeleteOutlined, EditOutlined, PlusOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { Category, CategoryInput } from '../../../api/types';
import { EmptyState } from '../../../components/EmptyState';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { catalogKeys, createCategory, deleteCategory, listCategories, updateCategory } from '../api';
import { apiErrorMessage, applyServerFieldErrors } from '../formErrors';
import { trimToNull } from '../validation';

interface CategoryFormValues {
  name: string;
  description?: string;
}

const FORM_FIELDS = ['name', 'description'] as const;

export default function CategoryPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<CategoryFormValues>();
  const [modalOpen, setModalOpen] = useState(false);
  const [editing, setEditing] = useState<Category | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const categoriesQuery = useQuery({
    queryKey: catalogKeys.categories(),
    queryFn: listCategories,
  });

  const openCreate = () => {
    setEditing(null);
    setFormError(null);
    setModalOpen(true);
  };

  const openEdit = (category: Category) => {
    setEditing(category);
    setFormError(null);
    setModalOpen(true);
  };

  const saveMutation = useMutation({
    mutationFn: (values: CategoryFormValues) => {
      const input: CategoryInput = {
        name: values.name.trim(),
        description: trimToNull(values.description),
      };
      return editing ? updateCategory(editing.id, input) : createCategory(input);
    },
    onSuccess: (category) => {
      message.success(editing ? `分类「${category.name}」已保存` : `分类「${category.name}」已创建`);
      setModalOpen(false);
      queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
    onError: (error) => {
      const { formLevelMessage } = applyServerFieldErrors(form, error, FORM_FIELDS);
      if (formLevelMessage) setFormError(formLevelMessage);
    },
  });

  const deleteMutation = useMutation({
    mutationFn: (id: number) => deleteCategory(id),
    onSuccess: () => {
      message.success('分类已删除');
      queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
    onError: (error) => message.error(apiErrorMessage(error)),
  });

  const columns: TableProps<Category>['columns'] = [
    {
      title: '分类名称',
      dataIndex: 'name',
      render: (name: string) => <Typography.Text strong>{name}</Typography.Text>,
    },
    {
      title: '描述',
      dataIndex: 'description',
      ellipsis: true,
      render: (description: string | null) =>
        description ?? <Typography.Text type="secondary">—</Typography.Text>,
    },
    {
      title: '图书数量',
      dataIndex: 'bookCount',
      width: 110,
      render: (count: number) => <Tag>{count} 本</Tag>,
    },
    {
      title: '操作',
      key: 'actions',
      width: 170,
      render: (_, category) => (
        <Space size={8}>
          <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(category)}>
            编辑
          </Button>
          <Popconfirm
            title="删除该分类？"
            description="仍有图书引用的分类不能删除。"
            okText="确认删除"
            okButtonProps={{
              danger: true,
              loading: deleteMutation.isPending && deleteMutation.variables === category.id,
            }}
            cancelText="取消"
            onConfirm={() => deleteMutation.mutate(category.id)}
          >
            <Button size="small" danger icon={<DeleteOutlined />} disabled={deleteMutation.isPending}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      ),
    },
  ];

  return (
    <section aria-label="分类管理">
      <Space style={{ width: '100%', justifyContent: 'space-between', marginBottom: 16 }} wrap>
        <Typography.Title level={3} style={{ margin: 0 }}>
          分类管理
        </Typography.Title>
        <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
          新建分类
        </Button>
      </Space>

      {categoriesQuery.isPending ? (
        <PageLoading tip="分类加载中…" />
      ) : categoriesQuery.isError ? (
        <PageError error={categoriesQuery.error} onRetry={() => categoriesQuery.refetch()} />
      ) : (
        <Table<Category>
          rowKey="id"
          columns={columns}
          dataSource={categoriesQuery.data}
          loading={categoriesQuery.isFetching}
          pagination={false}
          scroll={{ x: 560 }}
          locale={{
            emptyText: (
              <EmptyState
                description="还没有分类，先创建一个吧"
                action={
                  <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
                    新建分类
                  </Button>
                }
              />
            ),
          }}
        />
      )}

      <Modal
        title={editing ? `编辑分类：${editing.name}` : '新建分类'}
        open={modalOpen}
        onOk={() => form.submit()}
        onCancel={() => setModalOpen(false)}
        okText="保存"
        cancelText="取消"
        confirmLoading={saveMutation.isPending}
        cancelButtonProps={{ disabled: saveMutation.isPending }}
        destroyOnHidden
      >
        {formError && (
          <Alert
            type="error"
            showIcon
            closable
            title="提交失败"
            description={formError}
            onClose={() => setFormError(null)}
            style={{ marginBottom: 16 }}
          />
        )}
        <Form<CategoryFormValues>
          form={form}
          layout="vertical"
          initialValues={
            editing
              ? { name: editing.name, description: editing.description ?? undefined }
              : { name: '', description: undefined }
          }
          onFinish={(values) => {
            setFormError(null);
            saveMutation.mutate(values);
          }}
        >
          <Form.Item
            name="name"
            label="分类名称"
            rules={[
              { required: true, whitespace: true, message: '请输入分类名称' },
              { max: 50, message: '分类名称不能超过 50 字' },
            ]}
          >
            <Input placeholder="分类名称（必填，≤50 字，不区分大小写唯一）" maxLength={50} allowClear />
          </Form.Item>
          <Form.Item
            name="description"
            label="描述"
            rules={[{ max: 200, message: '描述不能超过 200 字' }]}
          >
            <Input.TextArea rows={3} maxLength={200} showCount placeholder="描述（可选，≤200 字）" />
          </Form.Item>
        </Form>
      </Modal>
    </section>
  );
}