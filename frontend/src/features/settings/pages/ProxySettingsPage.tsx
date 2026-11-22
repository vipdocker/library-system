import { useRef, useState } from 'react';
import { Alert, App, Button, Form, Input, Space, Switch, Typography } from 'antd';
import type { SwitchProps } from 'antd';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { ProxySettings, ProxySettingsInput } from '../../../api/types';
import { ApiError } from '../../../api/client';
import { errorMessage } from '../../../api/errors';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { getProxySettings, proxySettingsKey, saveProxySettings } from '../api';

interface Values { enabled: boolean; host: string; port: string }
function formValues(settings: ProxySettings): Values { return { enabled: settings.enabled, host: settings.host ?? '', port: settings.port == null ? '' : String(settings.port) }; }
function ProxySwitch(props: SwitchProps) {
  const [focused, setFocused] = useState(false);
  return <span onFocusCapture={() => setFocused(true)} onBlurCapture={() => setFocused(false)}>
    <Switch {...props} aria-label="启用备用代理" style={{ outline: focused ? '2px solid var(--claude-link)' : undefined, outlineOffset: 2 }} />
  </span>;
}
export default function ProxySettingsPage() {
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [form] = Form.useForm<Values>();
  const [baseline, setBaseline] = useState<ProxySettings>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const locked = useRef(false);
  const query = useQuery({ queryKey: proxySettingsKey, queryFn: ({ signal }) => getProxySettings(signal), retry: false });
  // Initialize once; background refetches never replace in-progress input.
  if (query.data && !baseline) setBaseline(query.data);
  const mutation = useMutation({
    retry: false,
    mutationFn: (input: ProxySettingsInput) => saveProxySettings(input),
    onSuccess: (settings) => {
      setBaseline(settings); form.setFieldsValue(formValues(settings));
      queryClient.setQueryData(proxySettingsKey, settings);
      message.success('代理配置已保存，对下一次查询生效');
    },
    onError: (failure) => {
      if (failure instanceof ApiError) {
        const fieldErrors = failure.fieldErrors.filter(({ field }) => ['enabled', 'host', 'port'].includes(field));
        form.setFields(fieldErrors.map(({ field, message: text }) => ({ name: field as keyof Values, errors: [text] })));
        if (failure.code === 'NETWORK_ERROR') setError('保存结果未知，请重新读取配置核对；输入已保留，不会自动重发。');
        else if (!fieldErrors.length) setError(errorMessage(failure.code));
      } else setError(errorMessage('NETWORK_ERROR'));
    },
    onSettled: () => { locked.current = false; setSaving(false); },
  });
  function submit(values: Values) {
    if (locked.current || !baseline) return;
    locked.current = true; setSaving(true); setError(undefined);
    const input: ProxySettingsInput = { enabled: values.enabled, host: values.host.trim() || null, port: values.port.trim() ? Number(values.port.trim()) : null };
    mutation.mutate(input);
  }
  if (!baseline) {
    if (query.isError) return <PageError error={query.error} onRetry={() => query.refetch()} />;
    return <PageLoading tip="代理配置加载中…" />;
  }
  return <section aria-label="代理配置" style={{ maxWidth: 720 }}>
    <Typography.Title level={3}>代理配置</Typography.Title>
    <Typography.Paragraph>优先直连，连接失败时才尝试已启用代理；变更对下一次查询生效</Typography.Paragraph>
    <Typography.Paragraph type="secondary">仅影响服务器书目与封面查询，不改变浏览器或系统代理。地址示例不代表已启用；不支持认证凭据或 PAC。</Typography.Paragraph>
    <div style={{ padding: 'clamp(16px, 3vw, 24px)', background: 'var(--claude-surface-soft)', borderRadius: 'var(--claude-radius-lg)' }}>
      {error && <Alert type="error" showIcon title={error} style={{ marginBottom: 16 }} />}
      {error?.includes('结果未知') && <Button disabled={saving} style={{ marginBottom: 16 }} onClick={async () => {
        try { const current = await getProxySettings(); setError(`服务器当前：${current.enabled ? '已启用' : '未启用'} · ${current.host ?? '空'} · ${current.port ?? '空'} · 版本 ${current.revision}。输入仍保留，请核对后决定。`); }
        catch { setError('重新读取失败，请稍后核对；不要重复提交。'); }
      }}>重新读取核对</Button>}
      <Form<Values> form={form} layout="vertical" initialValues={formValues(baseline)} disabled={saving} onFinish={submit}>
        <Form.Item name="enabled" label="启用备用代理" valuePropName="checked"><ProxySwitch /></Form.Item>
        <Form.Item name="host" label="代理主机" dependencies={['enabled', 'port']} rules={[{ validator: (_, value: string) => {
          if (!value?.trim() && (form.getFieldValue('enabled') || form.getFieldValue('port')?.trim())) return Promise.reject(new Error('请输入完整代理主机'));
          return Promise.resolve();
        } }]} extra="主机名或 IP 地址；最终合法性由服务器校验">
          <Input placeholder="例如 127.0.0.1" autoComplete="off" />
        </Form.Item>
        <Form.Item name="port" label="代理端口" dependencies={['enabled', 'host']} rules={[{ validator: (_, value: string) => {
          const text = value?.trim() ?? '';
          if (!text && (form.getFieldValue('enabled') || form.getFieldValue('host')?.trim())) return Promise.reject(new Error('请输入完整代理端口'));
          if (text && (!/^\d+$/.test(text) || Number(text) < 1 || Number(text) > 65535)) return Promise.reject(new Error('端口须为 1 至 65535 的整数'));
          return Promise.resolve();
        } }]}>
          <Input inputMode="numeric" placeholder="例如 9999" autoComplete="off" />
        </Form.Item>
        <Form.Item><Button type="primary" htmlType="submit" aria-label="保存配置" aria-busy={saving} disabled={saving} loading={saving}>保存配置</Button></Form.Item>
      </Form>
      <Space orientation="vertical" size={4}>
        <Typography.Text>配置版本：{baseline.revision}</Typography.Text>
        <Typography.Text type="secondary">最近更新：{baseline.updatedAt ? new Date(baseline.updatedAt).toLocaleString() : '尚未保存'}</Typography.Text>
        <Typography.Text type="secondary">版本仅供查看；跨窗口保存以最后一次成功保存为准。</Typography.Text>
      </Space>
    </div>
  </section>;
}
