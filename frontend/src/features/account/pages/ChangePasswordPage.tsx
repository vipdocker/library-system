// 修改本人口令（AC-AUTH-10，D4①；design-v0.1 第 8.4 / 10.1 / 10.2 节，T14）。
// 旧口令 + 新口令 + 确认新口令；前端按第 7 节口令策略校验；提交 PUT /api/auth/password。
// 状态按第 10.2 节：提交中按钮 loading 并禁用；错误用 Alert 显示中文文案（fieldErrors 映射到表单项）；
// 成功提示后清空表单；401 会话过期由 client.ts 全局处理（SessionGuard）。
import { useState } from 'react';
import { Alert, App, Button, Card, Form, Input, Typography } from 'antd';
import { useMutation } from '@tanstack/react-query';
import { changePassword } from '../api';
import { applyServerFieldErrors } from '../formErrors';
import { confirmPasswordRules, oldPasswordRules, passwordRules } from '../validation';

interface ChangePasswordFormValues {
  oldPassword: string;
  newPassword: string;
  confirmPassword: string;
}

export default function ChangePasswordPage() {
  const { message } = App.useApp();
  const [form] = Form.useForm<ChangePasswordFormValues>();
  const [formError, setFormError] = useState<string | null>(null);

  const mutation = useMutation({
    mutationFn: (values: ChangePasswordFormValues) =>
      changePassword({ oldPassword: values.oldPassword, newPassword: values.newPassword }),
    onSuccess: () => {
      message.success('口令已修改，当前登录保持有效，下次登录请使用新口令');
      form.resetFields();
      setFormError(null);
    },
    onError: (error) => {
      const mapping = applyServerFieldErrors(form, error, ['oldPassword', 'newPassword']);
      setFormError(mapping.formLevelMessage);
    },
  });

  const handleFinish = (values: ChangePasswordFormValues) => {
    setFormError(null);
    mutation.mutate(values);
  };

  return (
    <Card style={{ maxWidth: 480 }}>
      <Typography.Title level={3}>修改口令</Typography.Title>
      <Typography.Paragraph type="secondary">
        修改成功后当前登录保持有效，之后登录只能使用新口令。
      </Typography.Paragraph>
      <Form form={form} layout="vertical" onFinish={handleFinish} disabled={mutation.isPending}>
        {formError && (
          <Alert type="error" showIcon title={formError} style={{ marginBottom: 16 }} />
        )}
        <Form.Item name="oldPassword" label="旧口令" rules={oldPasswordRules}>
          <Input.Password maxLength={64} autoComplete="current-password" />
        </Form.Item>
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
          rules={confirmPasswordRules()}
        >
          <Input.Password maxLength={64} autoComplete="new-password" />
        </Form.Item>
        <Form.Item>
          <Button
            type="primary"
            htmlType="submit"
            loading={mutation.isPending}
            disabled={mutation.isPending}
          >
            修改口令
          </Button>
        </Form.Item>
      </Form>
    </Card>
  );
}