// 公共错误态（AC-UI-01 / AC-UI-03）：错误码映射中文文案 + 可选"重试"按钮；503 提示"系统繁忙"。
import { Alert, Button } from 'antd';
import { ApiError } from '../api/client';
import { errorMessage } from '../api/errors';

export interface PageErrorProps {
  error: unknown;
  onRetry?: () => void;
}

export function PageError({ error, onRetry }: PageErrorProps) {
  const apiError = error instanceof ApiError ? error : null;
  const description =
    apiError && (apiError.code === 'DB_BUSY' || apiError.status === 503)
      ? '系统繁忙，请稍后再试'
      : errorMessage(apiError ? apiError.code : 'NETWORK_ERROR');

  return (
    <Alert
      type="error"
      showIcon
      title="加载失败"
      description={description}
      action={
        onRetry ? (
          <Button size="small" onClick={onRetry}>
            重试
          </Button>
        ) : undefined
      }
    />
  );
}