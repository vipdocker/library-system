// 管理员重置读者口令弹窗（AC-RDR-05 UI / AC-UI-02）：口令策略校验 + 二次输入确认 + fieldErrors 映射。
import { useState } from 'react';
import { Alert, App, Form, Input, Modal } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { resetReaderPassword } from '../api';
import { applyServerFieldErrors } from '../formErrors';
import { passwordRules } from '../validation';

interface ResetPasswordFormValues {
  newPassword: string;
  confirmPassword: string;
}

export interface ResetPasswordModalProps {
  readerId: number;
  readerName: string;
  open: boolean;
  onClose: () => void;
}

export function ResetPasswordModal({ readerId, readerName, open, onClose }: ResetPasswordModalProps) {
  const { message } = App.useApp();
  const [form] = Form.useForm<ResetPasswordFormValues>();
  const [formError, setFormError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (newPassword: string) => resetReaderPassword(readerId, { newPassword }),
    onSuccess: () => {
      message.success('口令已重置，请通知读者使用新口令登录');
      form.resetFields();
      setFormError(null);
      onClose();
    },
    onError: (error) => {
      const mapping = applyServerFieldErrors(form, error, ['newPassword']);
      setFormError(mapping.formLevelMessage);
    },
  });

  const handleFinish = (values: ResetPasswordFormValues) => {
    setFormError(null);
    mutation.mutate(values.newPassword);
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
      title={`重置口令：${readerName}`}
      okText="重置"
      cancelText="取消"
      forceRender
      confirmLoading={mutation.isPending}
      okButtonProps={{ disabled: mutation.isPending }}
      cancelButtonProps={{ disabled: mutation.isPending }}
      onOk={() => form.submit()}
      onCancel={handleCancel}
    >
      <Form form={form} layout="vertical" onFinish={handleFinish} disabled={mutation.isPending}>
        {formError && (
          <Alert type="error" showIcon title={formError} style={{ marginBottom: 16 }} />
        )}
        <Form.Item
          name="newPassword"
          label="新口令"
          rules={passwordRules('新口令')}
          extra="8–64 位，至少包含一个字母和一个数字"
        >
          <Input.Password maxLength={64} autoComplete="new-password" />
        </Form.Item>
        <Form.Item
          name="confirmPassword"
          label="确认新口令"
          dependencies={['newPassword']}
          rules={[
            { required: true, message: '请再次输入新口令' },
            ({ getFieldValue }) => ({
              validator(_, value: string) {
                if (!value || getFieldValue('newPassword') === value) {
                  return Promise.resolve();
                }
                return Promise.reject(new Error('两次输入的口令不一致'));
              },
            }),
          ]}
        >
          <Input.Password maxLength={64} autoComplete="new-password" />
        </Form.Item>
      </Form>
    </Modal>
  );
}