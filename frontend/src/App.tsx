// Provider 组合：Router > QueryClient > antd ConfigProvider(zh_CN) > antd App > AuthProvider。
// SessionGuard 监听 client 的 401 全局事件（设计 10.2 节）。
import { App as AntdApp, ConfigProvider, type ThemeConfig } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import { queryClient } from './api/queryClient';
import { AuthProvider } from './auth/AuthContext';
import { SessionGuard } from './auth/SessionGuard';
import { AppRoutes } from './app/router';

// 图书馆主题：墨绿主色 + 暖纸底色；语义色与品牌色保持一致。
const theme: ThemeConfig = {
  token: {
    colorPrimary: '#1e6b52',
    colorInfo: '#2f6f8f',
    colorSuccess: '#3d8b51',
    colorWarning: '#c9861d',
    colorError: '#b23a32',
    colorBgLayout: '#f5f2ea',
    colorBgContainer: '#fffdf7',
    colorTextBase: '#22302a',
    borderRadius: 8,
    fontFamily:
      "-apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
  },
  components: {
    Layout: {
      siderBg: '#152e26',
      headerBg: '#152e26',
      triggerBg: '#1e4234',
    },
    Menu: {
      darkItemBg: '#152e26',
      darkItemSelectedBg: '#1e6b52',
      darkItemColor: 'rgba(245, 242, 234, 0.72)',
      darkItemSelectedColor: '#fffdf7',
    },
  },
};

export default function App() {
  return (
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <ConfigProvider locale={zhCN} theme={theme}>
          <AntdApp>
            <AuthProvider>
              <SessionGuard />
              <AppRoutes />
            </AuthProvider>
          </AntdApp>
        </ConfigProvider>
      </QueryClientProvider>
    </BrowserRouter>
  );
}