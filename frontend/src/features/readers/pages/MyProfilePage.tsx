// 我的资料（AC-RDR-06、AC-UI-01）：读者本人的卡号、姓名、联系方式、在借数与逾期数。
// 数据来自 GET /api/readers/me；只读页面，状态按第 10.2 节：加载 / 错误（重试）/ 有数据。
import { Card, Descriptions, Typography } from 'antd';
import { useQuery } from '@tanstack/react-query';
import dayjs from 'dayjs';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { getMyReaderProfile, readersKeys } from '../api';
import { ReaderStatusTag } from '../components/ReaderStatusTag';

export default function MyProfilePage() {
  const { data: reader, isPending, isError, error, refetch } = useQuery({
    queryKey: readersKeys.me(),
    queryFn: getMyReaderProfile,
  });

  if (isPending) {
    return <PageLoading />;
  }

  if (isError || !reader) {
    return <PageError error={error} onRetry={() => void refetch()} />;
  }

  return (
    <Card>
      <Typography.Title level={3}>我的资料</Typography.Title>
      <Descriptions
        bordered
        column={{ xs: 1, sm: 2 }}
        size="middle"
        items={[
          { key: 'cardNo', label: '卡号', children: reader.cardNo },
          { key: 'name', label: '姓名', children: reader.name },
          { key: 'username', label: '用户名', children: reader.username },
          {
            key: 'status',
            label: '状态',
            children: <ReaderStatusTag status={reader.status} />,
          },
          { key: 'phone', label: '电话', children: reader.phone ?? '未填写' },
          { key: 'email', label: '邮箱', children: reader.email ?? '未填写' },
          { key: 'activeLoanCount', label: '在借数量', children: reader.activeLoanCount },
          {
            key: 'overdueLoanCount',
            label: '逾期数量',
            children:
              reader.overdueLoanCount > 0 ? (
                <Typography.Text type="danger">{reader.overdueLoanCount}</Typography.Text>
              ) : (
                reader.overdueLoanCount
              ),
          },
          {
            key: 'createdAt',
            label: '创建时间',
            children: dayjs(reader.createdAt).format('YYYY-MM-DD'),
          },
        ]}
      />
    </Card>
  );
}