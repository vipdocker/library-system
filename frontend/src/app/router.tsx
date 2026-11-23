// 全部路由（设计 10.1 节，T02 一次注册；页面组件懒加载）。
// 各占位页由对应任务替换实现：T08 catalog、T09 readers、T10 circulation、T11 stats、T14 account。
// /account/password 依据用户决定 D4① 纳入。
import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router';
import { RequireAuth } from '../auth/RequireAuth';
import { RequireRole } from '../auth/RequireRole';
import LoginPage from '../auth/LoginPage';
import { PageLoading } from '../components/PageLoading';
import ForbiddenPage from '../components/ForbiddenPage';
import NotFoundPage from '../components/NotFoundPage';
import { AppLayout } from './layouts/AppLayout';
import { RootRedirect } from './RootRedirect';

const BookSearchPage = lazy(() => import('../features/catalog/pages/BookSearchPage'));
const BookDetailPage = lazy(() => import('../features/catalog/pages/BookDetailPage'));
const AdminBookListPage = lazy(() => import('../features/catalog/pages/AdminBookListPage'));
const BookFormPage = lazy(() => import('../features/catalog/pages/BookFormPage'));
const SettingsPage = lazy(() => import('../features/settings/pages/SettingsPage'));
const CategoryPage = lazy(() => import('../features/catalog/pages/CategoryPage'));

const AdminReaderListPage = lazy(() => import('../features/readers/pages/AdminReaderListPage'));
const ReaderDetailPage = lazy(() => import('../features/readers/pages/ReaderDetailPage'));
const MyProfilePage = lazy(() => import('../features/readers/pages/MyProfilePage'));

const CirculationDeskPage = lazy(() => import('../features/circulation/pages/CirculationDeskPage'));
const AdminLoanListPage = lazy(() => import('../features/circulation/pages/AdminLoanListPage'));
const MyLoansPage = lazy(() => import('../features/circulation/pages/MyLoansPage'));

const DashboardPage = lazy(() => import('../features/stats/pages/DashboardPage'));
const StatsPage = lazy(() => import('../features/stats/pages/StatsPage'));
const OverdueReportPage = lazy(() => import('../features/stats/pages/OverdueReportPage'));

const ChangePasswordPage = lazy(() => import('../features/account/pages/ChangePasswordPage'));
const NotificationInboxPage = lazy(() => import('../features/notifications/pages/NotificationInboxPage'));
const DunningCaseListPage = lazy(() => import('../features/dunning/pages/DunningCaseListPage'));

export function AppRoutes() {
  return (
    <Suspense fallback={<PageLoading />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route
          path="/"
          element={
            <RequireAuth>
              <AppLayout />
            </RequireAuth>
          }
        >
          <Route index element={<RootRedirect />} />
          <Route path="books" element={<BookSearchPage />} />
          <Route path="books/:id" element={<BookDetailPage />} />
          <Route
            path="my/loans"
            element={
              <RequireRole role="READER">
                <MyLoansPage />
              </RequireRole>
            }
          />
          <Route
            path="my/profile"
            element={
              <RequireRole role="READER">
                <MyProfilePage />
              </RequireRole>
            }
          />
          <Route path="my/notifications" element={<RequireRole role="READER"><NotificationInboxPage /></RequireRole>} />
          <Route path="account/password" element={<ChangePasswordPage />} />
          <Route path="admin" element={<RequireRole role="ADMIN" />}>
            <Route path="dashboard" element={<DashboardPage />} />
            <Route path="books" element={<AdminBookListPage />} />
            <Route path="books/new" element={<BookFormPage />} />
            <Route path="books/:id/edit" element={<BookFormPage />} />
            <Route path="categories" element={<CategoryPage />} />
            <Route path="readers" element={<AdminReaderListPage />} />
            <Route path="readers/:id" element={<ReaderDetailPage />} />
            <Route path="circulation" element={<CirculationDeskPage />} />
            <Route path="loans" element={<AdminLoanListPage />} />
            <Route path="reports/overdue" element={<OverdueReportPage />} />
            <Route path="dunning" element={<DunningCaseListPage />} />
            <Route path="stats" element={<StatsPage />} />
            <Route path="settings" element={<Navigate to="/admin/settings/proxy" replace />} />
            <Route path="settings/proxy" element={<SettingsPage />} />
            <Route path="settings/showapi" element={<SettingsPage />} />
          </Route>
          <Route path="403" element={<ForbiddenPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Routes>
    </Suspense>
  );
}