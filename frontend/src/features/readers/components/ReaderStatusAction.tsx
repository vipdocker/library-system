// 停用 / 启用读者（AC-RDR-02 / AC-UI-02）：Popconfirm 二次确认；status 变更由服务端同步账号 enabled。
import { App, Button, Popconfirm } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import type { Reader, ReaderStatus } from '../../../api/types';
import { readersKeys, updateReader } from '../api';
import { apiErrorMessage } from '../formErrors';

export interface ReaderStatusActionProps {
  reader: Reader;
  size?: 'small' | 'middle' | 'large';
}

export function ReaderStatusAction({ reader, size = 'small' }: ReaderStatusActionProps) {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const disabling = reader.status === 'ACTIVE';

  const mutation = useMutation({
    mutationFn: (status: ReaderStatus) =>
      updateReader(reader.id, {
        name: reader.name,
        phone: reader.phone,
        email: reader.email,
        status,
      }),
    onSuccess: (updated) => {
      message.success(
        updated.status === 'DISABLED' ? '读者已停用，账号同步禁用' : '读者已启用，账号恢复登录',
      );
      void queryClient.invalidateQueries({ queryKey: readersKeys.all });
    },
    onError: (error) => message.error(apiErrorMessage(error)),
  });

  return (
    <Popconfirm
      title={disabling ? '停用读者' : '启用读者'}
      description={
        disabling
          ? `停用后「${reader.name}」不能登录、借书或续借。确认停用？`
          : `确认启用「${reader.name}」？启用后可正常登录和借书。`
      }
      okText="确认"
      cancelText="取消"
      okButtonProps={{ danger: disabling, loading: mutation.isPending }}
      onConfirm={() => mutation.mutate(disabling ? 'DISABLED' : 'ACTIVE')}
    >
      <Button size={size} danger={disabling} loading={mutation.isPending}>
        {disabling ? '停用' : '启用'}
      </Button>
    </Popconfirm>
  );
}