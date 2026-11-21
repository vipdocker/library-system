import { useRef, useState } from 'react';
import { Alert, App as AntdApp, Button, Form, Input, Modal, Radio, Space } from 'antd';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../../../api/client';
import { errorMessage } from '../../../api/errors';
import type { DunningInput, DunningMethod, OverdueItem } from '../../../api/types';
import { dunningKeys, normalizeDunningNote, registerDunning } from '../api';
import { methodLabels } from '../presentation';
import '../dunning.css';

interface Props { item: OverdueItem; onClose: () => void; onRefresh: () => void }

export function DunningRegistrationModal({ item, onClose, onRefresh }: Props) {
  const [form] = Form.useForm<DunningInput>();
  const { message } = AntdApp.useApp();
  const client = useQueryClient();
  const writeLock = useRef(false);
  const [failure, setFailure] = useState<string | null>(null);
  const mutation = useMutation({
    retry: false,
    mutationFn: (input: DunningInput) => registerDunning(item.loanId, input),
    onSuccess: async () => {
      message.success('已登记催还并生成站内提醒，不代表已送达');
      onClose();
      await Promise.all([
        client.invalidateQueries({ queryKey: dunningKeys.all }),
        client.invalidateQueries({ queryKey: ['reports', 'overdue'] }),
      ]);
    },
    onError: (error) => {
      const apiError = error instanceof ApiError ? error : null;
      setFailure(errorMessage(apiError?.status === 503 ? 'DB_BUSY' : apiError?.code ?? 'NETWORK_ERROR'));
      if (apiError) {
        const fields = apiError.fieldErrors.filter(field => field.field === 'method' || field.field === 'note');
        form.setFields(fields.map(field => ({ name: field.field as 'method' | 'note', errors: [field.message] })));
      }
    },
    onSettled: () => { writeLock.current = false; },
  });
  const submit = (input: DunningInput) => {
    if (writeLock.current) return;
    writeLock.current = true;
    setFailure(null);
    mutation.mutate(input);
  };

  return (
    <Modal open title="登记催还" width={620} footer={null} onCancel={() => { if (!writeLock.current) onClose(); }} closable={!mutation.isPending} mask={{ closable: !mutation.isPending }} keyboard={!mutation.isPending} className="dunning-modal">
      <div className="dunning-loan-summary">
        <strong>借阅号：{item.loanId}</strong>
        <div>{item.reader.name}</div><code>{item.reader.cardNo}</code>
        <div>{item.book.title}</div><code>{item.copyBarcode}</code>
        <div>应还日：<span>{item.dueDate}</span></div>
      </div>
      <Alert type="info" showIcon title="登记将生成固定模板站内提醒，读者仅在登录系统后可查看" description="电话等方式只作人工记录；系统不会发送电话、短信或邮件，不代表提醒已送达。" />
      <Form form={form} layout="vertical" onFinish={submit} disabled={mutation.isPending} className="dunning-form">
        <Form.Item name="method" label="催还方式" rules={[{ required: true, message: '请选择催还方式' }, { validator: (_, value) => !value || Object.hasOwn(methodLabels, value) ? Promise.resolve() : Promise.reject(new Error('请选择合法催还方式')) }]}>
          <Radio.Group options={(Object.keys(methodLabels) as DunningMethod[]).map(value => ({ value, label: methodLabels[value] }))} />
        </Form.Item>
        <Form.Item name="note" label="管理员备注" extra="备注仅管理员可见，不会出现在读者站内提醒中；可留空，最多500字符。" rules={[{ validator: (_, value: string | undefined) => (normalizeDunningNote(value)?.length ?? 0) <= 500 ? Promise.resolve() : Promise.reject(new Error('备注最多500字符')) }]}>
          <Input.TextArea rows={4} showCount />
        </Form.Item>
        {failure && <Alert type="error" showIcon title="登记未成功" description={<><div>{failure}</div><div>请刷新核对当前借阅与催还历史，再决定是否重新确认登记。</div></>} />}
        <Space wrap className="dunning-actions">
          <Button type="primary" htmlType="submit" loading={mutation.isPending} disabled={mutation.isPending} aria-label="确认登记">确认登记</Button>
          <Button onClick={onClose} disabled={mutation.isPending} aria-label="取消登记">取消登记</Button>
          {failure && <Button onClick={onRefresh} disabled={mutation.isPending} aria-label="刷新逾期报表">刷新逾期报表</Button>}
        </Space>
      </Form>
    </Modal>
  );
}
