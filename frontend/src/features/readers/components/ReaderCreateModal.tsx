// 新建读者弹窗（AC-RDR-01 / AC-UI-02）：前端校验 + 服务端 fieldErrors 映射 + 提交中防重复。
import { useState } from 'react';
import { Alert, App, Form, Input, Modal } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { ApiError } from '../../../api/client';
import type { Reader, ReaderCreateInput } from '../../../api/types';
import { createReader } from '../api';
import { applyServerFieldErrors } from '../formErrors';
import {
  passwordRules,
  readerEmailRules,
  readerNameRules,
  readerPhoneRules,
  readerUsernameRules,
  trimToNull,
} from '../validation';

interface ReaderCreateFormValues {
  name: string;
  phone?: string;
  email?: string;
  username: string;
  initialPassword: string;
}

const KNOWN_FIELDS = ['name', 'phone', 'email', 'username', 'initialPassword'] as const;

export interface ReaderCreateModalProps {
  open: boolean;
  onClose: () => void;
  onCreated: (reader: Reader) => void;
}

export function ReaderCreateModal({ open, onClose, onCreated }: ReaderCreateModalProps) {
  const { message } = App.useApp();
  const [form] = Form.useForm<ReaderCreateFormValues>();
  const [formError, setFormError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (input: ReaderCreateInput) => createReader(input),
    onSuccess: (reader) => {
      message.success(`读者已创建，卡号 ${reader.cardNo}`);
      form.resetFields();
      setFormError(null);
      onCreated(reader);
    },
    onError: (error) => {
      // 用户名重复（409，无 fieldErrors）直接落到 username 表单项
      if (error instanceof ApiError && error.code === 'USERNAME_DUPLICATE') {
        form.setFields([{ name: 'username', errors: ['用户名已存在'] }]);
        setFormError(null);
        return;
      }
      const mapping = applyServerFieldErrors(form, error, KNOWN_FIELDS);
      setFormError(mapping.formLevelMessage);
    },
  });

  const handleFinish = (values: ReaderCreateFormValues) => {
    setFormError(null);
    mutation.mutate({
      name: values.name.trim(),
      phone: trimToNull(values.phone),
      email: trimToNull(values.email),
      username: values.username.trim(),
      initialPassword: values.initialPassword,
    });
  };

  const handleCancel = () => {
    if (mutation.isPending) return;
    form.resetFields();
    setFormError(null);
    onClose();
  };

  return (
    <Modal
      open={open}
      title="新建读者"
      okText="创建"
      cancelText="取消"
      forceRender
      confirmLoading={mutation.isPending}
      okButtonProps={{ disabled: mutation.isPending }}
      cancelButtonProps={{ disabled: mutation.isPending }}
      onOk={() => form.submit()}
      onCancel={handleCancel}
    >
      <Form
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        disabled={mutation.isPending}
        requiredMark
      >
        {formError && (
          <Alert type="error" showIcon title={formError} style={{ marginBottom: 16 }} />
        )}
        <Form.Item name="name" label="姓名" rules={readerNameRules}>
          <Input maxLength={50} placeholder="读者姓名" autoComplete="off" />
        </Form.Item>
        <Form.Item name="phone" label="电话" rules={readerPhoneRules}>
          <Input maxLength={20} placeholder="可选，数字、+、-、空格" autoComplete="off" />
        </Form.Item>
        <Form.Item name="email" label="邮箱" rules={readerEmailRules}>
          <Input maxLength={100} placeholder="可选" autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="username"
          label="用户名"
          rules={readerUsernameRules}
          extra="同时创建读者登录账号，卡号由系统生成"
        >
          <Input maxLength={32} placeholder="3–32 位字母、数字、_、. 或 -" autoComplete="off" />
        </Form.Item>
        <Form.Item
          name="initialPassword"
          label="初始口令"
          rules={passwordRules('初始口令')}
          extra="8–64 位，至少包含一个字母和一个数字"
        >
          <Input.Password maxLength={64} placeholder="读者首次登录使用" autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}