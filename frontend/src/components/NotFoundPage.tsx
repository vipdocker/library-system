// 404 页：未匹配路由的兜底。
import { Button, Result } from 'antd';
import { SearchOutlined } from '@ant-design/icons';
import { useNavigate } from 'react-router';

export default function NotFoundPage() {
  const navigate = useNavigate();
  return (
    <Result
      icon={<SearchOutlined aria-hidden />}
      title="404"
      subTitle="页面不存在"
      extra={
        <Button type="primary" onClick={() => navigate('/')}>
          返回首页
        </Button>
      }
    />
  );
}