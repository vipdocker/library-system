// AC-S4/T2（showapi-ac-dod-v1）：ShowAPI AppKey 系统设置。
// AppKey 只短时存在于表单内存：直接 await API（不经 useMutation，避免 secret 进入 mutation variables 历史），
// 成功清空、失败/未知保留待人工核对；绝不写入 storage/URL/日志/查询缓存，GET 仅配置状态。
import { useRef, useState } from 'react';
import { Alert, App, Button, Form, Input, Radio, Space, Switch, Typography } from 'antd';
import type { SwitchProps } from 'antd';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { ShowApiSettings, ShowApiSettingsInput } from '../../../api/types';
import { ApiError } from '../../../api/client';
import { errorMessage } from '../../../api/errors';
import { PageError } from '../../../components/PageError';
import { PageLoading } from '../../../components/PageLoading';
import { getShowApiSettings, saveShowApiSettings, showApiSettingsKey } from '../api';

interface Values { enabled: boolean; appKeyAction: 'KEEP' | 'REPLACE' | 'CLEAR'; appKey: string }
function formValues(settings: ShowApiSettings): Values { return { enabled: settings.enabled, appKeyAction: 'KEEP', appKey: '' }; }
function FocusSwitch(props: SwitchProps) {
  const [focused, setFocused] = useState(false);
  return <span onFocusCapture={() => setFocused(true)} onBlurCapture={() => setFocused(false)}>
    <Switch {...props} aria-label="启用 ShowAPI" style={{ outline: focused ? '2px solid var(--claude-link)' : undefined, outlineOffset: 2 }} />
  </span>;
}
export default function ShowApiSettingsPage() {
  const queryClient = useQueryClient();
  const { message } = App.useApp();
  const [form] = Form.useForm<Values>();
  const [baseline, setBaseline] = useState<ShowApiSettings>();
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string>();
  const [recheck, setRecheck] = useState<string>();
  const locked = useRef(false);
  const query = useQuery({ queryKey: showApiSettingsKey, queryFn: ({ signal }) => getShowApiSettings(signal), retry: false });
  // 只初始化一次；后台重取不覆盖进行中的输入。
  if (query.data && !baseline) setBaseline(query.data);
  const enabledWatch = Form.useWatch('enabled', form);
  const actionWatch = Form.useWatch('appKeyAction', form);

  async function submit(values: Values) {
    if (locked.current || !baseline) return;
    const key = values.appKey.trim();
    if (values.appKeyAction === 'REPLACE' && (!key || key.length > 512)) {
      form.setFields([{ name: 'appKey', errors: ['AppKey 去除首尾空白后须为 1 至 512 个字符'] }]);
      return;
    }
    if (values.enabled && values.appKeyAction === 'CLEAR') { setError('清除 AppKey 只能在停用 ShowAPI 时进行'); return; }
    if (values.enabled && !baseline.appKeyConfigured && values.appKeyAction !== 'REPLACE') {
      setError('启用 ShowAPI 需要已配置的 AppKey 或输入新 AppKey'); return;
    }
    locked.current = true; setSaving(true); setError(undefined); setRecheck(undefined);
    const input: ShowApiSettingsInput = values.appKeyAction === 'REPLACE'
      ? { enabled: values.enabled, appKeyAction: 'REPLACE', appKey: key, expectedRevision: baseline.revision }
      : { enabled: values.enabled, appKeyAction: values.appKeyAction, expectedRevision: baseline.revision };
    try {
      const view = await saveShowApiSettings(input);
      setBaseline(view);
      form.setFieldsValue(formValues(view));
      queryClient.setQueryData(showApiSettingsKey, view);
      message.success('ShowAPI 配置已保存，对下一次查询生效');
    } catch (failure) {
      if (failure instanceof ApiError) {
        const fieldErrors = failure.fieldErrors.filter(({ field }) => ['enabled', 'appKeyAction', 'appKey'].includes(field));
        form.setFields(fieldErrors.map(({ field, message: text }) => ({ name: field as keyof Values, errors: [text] })));
        if (failure.code === 'NETWORK_ERROR') setError('保存结果未知，请重新读取配置核对；输入已保留，不会自动重发。');
        else if (failure.code === 'SETTINGS_CONFLICT') setError(`配置已被其他请求修改（服务器版本 ${typeof failure.details.currentRevision === 'number' ? failure.details.currentRevision : '未知'}）。请重新读取核对后再决定是否保存；输入已保留。`);
        else if (!fieldErrors.length) setError(errorMessage(failure.code));
      } else setError('保存结果未知，请重新读取配置核对；输入已保留，不会自动重发。');
    } finally { locked.current = false; setSaving(false); }
  }
  async function reread() {
    try {
      const current = await getShowApiSettings();
      setBaseline(current);
      queryClient.setQueryData(showApiSettingsKey, current);
      setError(undefined);
      setRecheck(`服务器当前：${current.enabled ? '已启用' : '未启用'} · AppKey ${current.appKeyConfigured ? '已配置' : '未配置'} · 版本 ${current.revision}。输入仍保留，请核对后决定是否保存。`);
    } catch { setRecheck('重新读取失败，请稍后核对；不要重复提交。'); }
  }
  if (!baseline) {
    if (query.isError) return <PageError error={query.error} onRetry={() => query.refetch()} />;
    return <PageLoading tip="ShowAPI 配置加载中…" />;
  }
  return <section aria-label="ShowAPI 配置" style={{ maxWidth: 720 }}>
    <Typography.Title level={3}>ShowAPI 书目来源</Typography.Title>
    <Typography.Paragraph>启用后可在图书表单按 ISBN 查询 ShowAPI 候选；保存配置不会连接 ShowAPI，对下一次查询生效。</Typography.Paragraph>
    <Typography.Paragraph type="secondary">AppKey 以明文保存在本机 SQLite 数据库，数据库文件与备份可能暴露它；清除配置不会擦除历史备份，如需彻底失效请到 ShowAPI 供应商处撤销该 Key。</Typography.Paragraph>
    <div style={{ padding: 'clamp(16px, 3vw, 24px)', background: 'var(--claude-surface-soft)', borderRadius: 'var(--claude-radius-lg)' }}>
      {error && <Alert type="error" showIcon title={error} style={{ marginBottom: 16 }} />}
      {(error?.includes('结果未知') || error?.includes('已被其他请求修改')) && <Button disabled={saving} style={{ marginBottom: 16 }} onClick={reread}>重新读取核对</Button>}
      {recheck && <Typography.Paragraph role="status" style={{ overflowWrap: 'anywhere' }}>{recheck}</Typography.Paragraph>}
      <Form<Values> form={form} layout="vertical" initialValues={formValues(baseline)} disabled={saving} onFinish={submit}>
        <Form.Item name="enabled" label="启用 ShowAPI" valuePropName="checked">
          <FocusSwitch onChange={(checked) => { if (checked && form.getFieldValue('appKeyAction') === 'CLEAR') form.setFieldValue('appKeyAction', 'KEEP'); }} />
        </Form.Item>
        <Form.Item name="appKeyAction" label="AppKey 操作">
          <Radio.Group aria-label="AppKey 操作" onChange={(event) => { if (event.target.value !== 'REPLACE') form.setFieldValue('appKey', ''); }} options={[
            { label: '保留现有', value: 'KEEP' },
            { label: '替换', value: 'REPLACE' },
            { label: '清除', value: 'CLEAR', disabled: enabledWatch === true },
          ]} />
        </Form.Item>
        <Form.Item name="appKey" label="AppKey" extra={actionWatch === 'REPLACE' ? '去除首尾空白后 1–512 个字符；保存成功后立即清空，不回显' : '仅在"替换"时输入；服务器不会返回已保存的 AppKey'}>
          <Input.Password autoComplete="new-password" visibilityToggle={false} disabled={saving || actionWatch !== 'REPLACE'} placeholder={actionWatch === 'REPLACE' ? '输入新的 AppKey' : '无需输入'} maxLength={1024} />
        </Form.Item>
        <Form.Item><Button type="primary" htmlType="submit" aria-label="保存配置" aria-busy={saving} disabled={saving} loading={saving}>保存配置</Button></Form.Item>
      </Form>
      <Space orientation="vertical" size={4}>
        <Typography.Text>AppKey 状态：{baseline.appKeyConfigured ? '已配置' : '未配置'}</Typography.Text>
        <Typography.Text>配置版本：{baseline.revision}</Typography.Text>
        <Typography.Text type="secondary">最近更新：{baseline.updatedAt ? new Date(baseline.updatedAt).toLocaleString() : '尚未保存'}</Typography.Text>
        <Typography.Text type="secondary">版本用于并发保护；保存冲突时请先重新读取核对，不会自动重发。</Typography.Text>
      </Space>
    </div>
  </section>;
}