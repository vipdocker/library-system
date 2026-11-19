// 403 页（AC-AUTH-09）：RequireRole 角色不符时跳转到这里。
import { Button, Result } from 'antd';
import { useNavigate } from 'react-router';

export default function ForbiddenPage() {
  const navigate = useNavigate();
  return (
    <Result
      status="403"
      title="403"
      subTitle="无权限访问该页面"
      extra={
        <Button type="primary" onClick={() => navigate('/')}>
          返回首页
        </Button>
      }
    />
  );
}