// Provider 组合：Router > QueryClient > antd ConfigProvider(zh_CN) > antd App > AuthProvider。
// SessionGuard 监听 client 的 401 全局事件（设计 10.2 节）。
import { App as AntdApp, ConfigProvider } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import { QueryClientProvider } from '@tanstack/react-query';
import { BrowserRouter } from 'react-router';
import { queryClient } from './api/queryClient';
import { AuthProvider } from './auth/AuthContext';
import { SessionGuard } from './auth/SessionGuard';
import { AppRoutes } from './app/router';

import { linearTheme } from './styles/linearTheme';

export default function App() {
  return (
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <ConfigProvider locale={zhCN} theme={linearTheme}>
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