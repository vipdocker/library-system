// 登录页（T02 实现，AC-AUTH-01 / AC-AUTH-09）。
// 登录失败用 errors.ts 的中文文案；成功后回到 redirect 指向的站内路径。
import { useEffect, useState } from 'react';
import { Alert, Button, Card, Form, Input, Typography } from 'antd';
import { BookOutlined, LockOutlined, UserOutlined } from '@ant-design/icons';
import { Navigate, useNavigate, useSearchParams } from 'react-router';
import { ApiError } from '../api/client';
import { errorMessage } from '../api/errors';
import type { LoginRequest } from '../api/types';
import { PageLoading } from '../components/PageLoading';
import { useAuth } from './AuthContext';
import { sanitizeRedirect } from './redirect';

export default function LoginPage() {
  const { status, login, clearLogoutIntent } = useAuth();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const redirect = sanitizeRedirect(searchParams.get('redirect'));
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  // I-09：到达登录页即消费"主动登出"意图（此时登出导航级联已结束），
  // 之后未登录直接访问受保护页恢复保留 redirect（AC-AUTH-09）。
  useEffect(() => {
    clearLogoutIntent?.();
  }, [clearLogoutIntent]);

  if (status === 'loading') return <PageLoading tip="正在确认登录状态…" />;
  if (status === 'authenticated') return <Navigate to={redirect} replace />;

  const onFinish = async (values: LoginRequest) => {
    setSubmitting(true);
    setError(null);
    try {
      await login(values.username.trim(), values.password);
      navigate(redirect, { replace: true });
    } catch (err) {
      setError(err instanceof ApiError ? errorMessage(err.code) : errorMessage('NETWORK_ERROR'));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-shell">
      <aside className="login-brand" aria-hidden="true">
        <div className="login-brand-copy">
          <BookOutlined className="login-brand-mark" />
          <h1>图书馆管理系统</h1>
          <p>借阅有序 · 书香常新</p>
          <ul>
            <li>图书与读者管理</li>
            <li>借阅 · 归还 · 续借</li>
            <li>检索与借阅统计</li>
          </ul>
        </div>
      </aside>
      <main className="login-main">
        <Card className="login-card" variant="outlined">
          <Typography.Title level={3} className="login-title">
            欢迎回来
          </Typography.Title>
          <Typography.Paragraph type="secondary">
            请使用管理员分配的账号登录
          </Typography.Paragraph>
          {error && (
            <Alert
              className="login-error"
              type="error"
              showIcon
              title={error}
              role="alert"
            />
          )}
          <Form<LoginRequest> layout="vertical" onFinish={onFinish} requiredMark={false} size="large">
            <Form.Item
              label="用户名"
              name="username"
              rules={[{ required: true, whitespace: true, message: '请输入用户名' }]}
            >
              <Input prefix={<UserOutlined />} placeholder="请输入用户名" autoComplete="username" autoFocus />
            </Form.Item>
            <Form.Item
              label="密码"
              name="password"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password prefix={<LockOutlined />} placeholder="请输入密码" autoComplete="current-password" />
            </Form.Item>
            <Form.Item>
              <Button type="primary" htmlType="submit" block loading={submitting}>
                登录
              </Button>
            </Form.Item>
          </Form>
          <Typography.Text type="secondary" className="login-footnote">
            本地开发环境 · 读者账号由管理员创建，不支持自助注册
          </Typography.Text>
        </Card>
      </main>
    </div>
  );
}