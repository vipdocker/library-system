// 读者详情（AC-RDR-02/05 UI、AC-UI-01/02）：资料编辑、重置口令、该读者的借阅列表。
// 停用/启用通过头部操作（Popconfirm 二次确认）；编辑表单只改姓名/电话/邮箱，status 原样带回。
import { useState } from 'react';
import { Alert, App, Button, Card, Form, Input, Space, Typography } from 'antd';
import { ArrowLeftOutlined, KeyOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link, useParams } from 'react-router';
import dayjs from 'dayjs';
import { ApiError } from '../../../api/client';
import type { Reader } from '../../../api/types';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { getReader, readersKeys, updateReader } from '../api';
import { applyServerFieldErrors } from '../formErrors';
import { readerEmailRules, readerNameRules, readerPhoneRules, trimToNull } from '../validation';
import { ReaderLoansPanel } from '../components/ReaderLoansPanel';
import { ReaderStatusAction } from '../components/ReaderStatusAction';
import { ReaderStatusTag } from '../components/ReaderStatusTag';
import { ResetPasswordModal } from '../components/ResetPasswordModal';

interface ReaderEditFormValues {
  name: string;
  phone?: string;
  email?: string;
}

function ReaderEditCard({ reader }: { reader: Reader }) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const [form] = Form.useForm<ReaderEditFormValues>();
  const [formError, setFormError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (values: ReaderEditFormValues) =>
      updateReader(reader.id, {
        name: values.name.trim(),
        phone: trimToNull(values.phone),
        email: trimToNull(values.email),
        status: reader.status,
      }),
    onSuccess: () => {
      message.success('资料已保存');
      setFormError(null);
      void queryClient.invalidateQueries({ queryKey: readersKeys.all });
    },
    onError: (error) => {
      const mapping = applyServerFieldErrors(form, error, ['name', 'phone', 'email', 'status']);
      setFormError(mapping.formLevelMessage);
    },
  });

  return (
    <Card title="资料编辑" size="small">
      <Form
        key={reader.id}
        form={form}
        layout="vertical"
        style={{ maxWidth: 480 }}
        initialValues={{
          name: reader.name,
          phone: reader.phone ?? undefined,
          email: reader.email ?? undefined,
        }}
        onFinish={(values) => {
          setFormError(null);
          mutation.mutate(values);
        }}
        disabled={mutation.isPending}
      >
        {formError && (
          <Alert type="error" showIcon title={formError} style={{ marginBottom: 16 }} />
        )}
        <Form.Item name="name" label="姓名" rules={readerNameRules}>
          <Input maxLength={50} autoComplete="off" />
        </Form.Item>
        <Form.Item name="phone" label="电话" rules={readerPhoneRules}>
          <Input maxLength={20} placeholder="可选" autoComplete="off" />
        </Form.Item>
        <Form.Item name="email" label="邮箱" rules={readerEmailRules}>
          <Input maxLength={100} placeholder="可选" autoComplete="off" />
        </Form.Item>
        <Form.Item style={{ marginBottom: 0 }}>
          <Button
            type="primary"
            htmlType="submit"
            loading={mutation.isPending}
            disabled={mutation.isPending}
          >
            保存
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}

export default function ReaderDetailPage() {
  const { id } = useParams();
  const readerId = Number(id);
  const validId = Number.isInteger(readerId) && readerId > 0;
  const [resetOpen, setResetOpen] = useState(false);

  const { data: reader, isPending, isError, error, refetch } = useQuery({
    queryKey: readersKeys.detail(readerId),
    queryFn: () => getReader(readerId),
    enabled: validId,
  });

  if (!validId) {
    return (
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <PageError error={new ApiError(404, 'READER_NOT_FOUND', '读者不存在或已被删除')} />
        <Link to="/admin/readers">返回读者列表</Link>
      </Space>
    );
  }

  if (isPending) {
    return <PageLoading />;
  }

  if (isError || !reader) {
    return (
      <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
        <PageError error={error} onRetry={() => void refetch()} />
        <Link to="/admin/readers">返回读者列表</Link>
      </Space>
    );
  }

  return (
    <Space orientation="vertical" size="middle" style={{ width: '100%' }}>
      <Space style={{ width: '100%', justifyContent: 'space-between' }} wrap>
        <Space align="center" size="middle">
          <Link to="/admin/readers">
            <Button icon={<ArrowLeftOutlined />}>返回列表</Button>
          </Link>
          <Typography.Title level={3} style={{ margin: 0 }}>
            {reader.name}
          </Typography.Title>
          <ReaderStatusTag status={reader.status} />
        </Space>
        <Space>
          <Button icon={<KeyOutlined />} onClick={() => setResetOpen(true)}>
            重置口令
          </Button>
          <ReaderStatusAction reader={reader} size="middle" />
        </Space>
      </Space>

      <Typography.Text type="secondary">
        {`卡号 ${reader.cardNo} · 用户名 ${reader.username} · 在借 ${reader.activeLoanCount} 本 · 逾期 ${reader.overdueLoanCount} 本 · 创建于 ${dayjs(reader.createdAt).format('YYYY-MM-DD')}`}
      </Typography.Text>

      <ReaderEditCard reader={reader} />

      <Card title="借阅记录" size="small">
        <ReaderLoansPanel readerId={reader.id} />
      </Card>

      <ResetPasswordModal
        readerId={reader.id}
        readerName={reader.name}
        open={resetOpen}
        onClose={() => setResetOpen(false)}
      />
    </Space>
  );
}