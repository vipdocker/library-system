// 应用布局：侧边菜单按角色渲染（AC-AUTH-09 / AC-UI-05），顶栏显示当前用户与登出。
import { createElement, useMemo, useState } from 'react';
import { Avatar, Button, Layout, Menu, Space, Tag, Typography, theme } from 'antd';
import { BookOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons';
import { Outlet, useLocation, useNavigate } from 'react-router';
import { useAuth } from '../../auth/AuthContext';
import { menuForRole, selectedMenuKey } from '../menu';

const { Header, Sider, Content } = Layout;

export function AppLayout() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [collapsed, setCollapsed] = useState(false);
  const { token } = theme.useToken();

  const entries = useMemo(() => menuForRole(user?.role ?? 'READER'), [user?.role]);
  const selectedKey = selectedMenuKey(location.pathname, entries);

  const menuItems = entries.map((entry) => ({
    key: entry.key,
    icon: createElement(entry.icon),
    label: entry.label,
  }));

  const onLogout = async () => {
    // I-09（approval-record §1.1）：先完成登出（清登录态）再跳转。
    // 竞态防护由 AuthContext 的登出意图（loggedOutExplicitly）与 SessionGuard 的
    // 迟到 401 忽略共同保证：RequireAuth 在登出级联中不追加 ?redirect=...（E2E E-01）。
    // 注意不能在响应返回前跳 /login：LoginPage 会把仍认证的用户弹回 /，引发新的竞态。
    await logout();
    navigate('/login', { replace: true });
  };

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        collapsible
        collapsed={collapsed}
        onCollapse={setCollapsed}
        breakpoint="lg"
        width={232}
        theme="dark"
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 10,
            padding: collapsed ? '18px 0' : '18px 20px',
            justifyContent: collapsed ? 'center' : 'flex-start',
            color: token.colorBgContainer,
          }}
        >
          <BookOutlined style={{ fontSize: 24, color: '#e8c98a' }} />
          {!collapsed && (
            <Typography.Text strong style={{ color: token.colorBgContainer, fontSize: 16 }}>
              图书馆管理系统
            </Typography.Text>
          )}
        </div>
        <Menu
          theme="dark"
          mode="inline"
          items={menuItems}
          selectedKeys={selectedKey ? [selectedKey] : []}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            paddingInline: 16,
            // I-03：antd Header 默认 line-height:64px 会让行内块（ellipsis 文本）基线沉底，
            // 窄屏下看起来像换行；flex 布局不需要它。
            lineHeight: 'normal',
          }}
        >
          {/* I-03：窄屏（390px）下压缩间距并限制用户名宽度，避免顶栏换行 */}
          <Space size="small">
            <Tag color={user?.role === 'ADMIN' ? 'gold' : 'green'} style={{ marginInlineEnd: 0 }}>
              {user?.role === 'ADMIN' ? '管理员' : '读者'}
            </Tag>
            <Space size={8} align="center" style={{ minWidth: 0 }}>
              <Avatar size="small" icon={<UserOutlined />} style={{ background: token.colorPrimary, flexShrink: 0 }} />
              <Typography.Text
                ellipsis={{ tooltip: user?.displayName ?? user?.username }}
                style={{ color: token.colorBgContainer, maxWidth: '6em', whiteSpace: 'nowrap' }}
              >
                {user?.displayName ?? user?.username}
              </Typography.Text>
            </Space>
            <Button
              type="text"
              icon={<LogoutOutlined />}
              style={{ color: token.colorBgContainer, whiteSpace: 'nowrap' }}
              onClick={onLogout}
            >
              退出登录
            </Button>
          </Space>
        </Header>
        <Content style={{ margin: 24 }}>
          <div
            style={{
              background: token.colorBgContainer,
              borderRadius: 12,
              padding: 24,
              minHeight: 'calc(100vh - 160px)',
            }}
          >
            <Outlet />
          </div>
        </Content>
      </Layout>
    </Layout>
  );
}