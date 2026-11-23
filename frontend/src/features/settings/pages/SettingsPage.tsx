// showapi-design-v1 §7：系统设置单菜单入口，容器内代理 / ShowAPI 页签；
// /admin/settings/proxy 旧 URL 保留，/admin/settings/showapi 可直达（路由驱动页签）。
import { Tabs, Typography } from 'antd';
import { useLocation, useNavigate } from 'react-router';
import ProxySettingsPage from './ProxySettingsPage';
import ShowApiSettingsPage from './ShowApiSettingsPage';

export default function SettingsPage() {
  const location = useLocation();
  const navigate = useNavigate();
  const activeKey = location.pathname.endsWith('/showapi') ? 'showapi' : 'proxy';
  return <section aria-label="系统设置">
    <Typography.Title level={3}>系统设置</Typography.Title>
    <Tabs
      activeKey={activeKey}
      onChange={(key) => navigate(`/admin/settings/${key}`)}
      items={[
        { key: 'proxy', label: '代理', children: <ProxySettingsPage /> },
        { key: 'showapi', label: 'ShowAPI', children: <ShowApiSettingsPage /> },
      ]}
    />
  </section>;
}