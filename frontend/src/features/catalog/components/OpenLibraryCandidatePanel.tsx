import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Checkbox, Flex, Form, Space, Typography } from 'antd';
import type { FormInstance } from 'antd';
import { ApiError } from '../../../api/client';
import { errorMessage } from '../../../api/errors';
import type { OpenLibraryDiagnostic, OpenLibraryLookup } from '../../../api/types';
import { lookupOpenLibrary } from '../api';
import { type CoverDraft, replacementFromBase64 } from '../coverDraft';
import { isValidIsbn, normalizeIsbn } from '../validation';
import { CoverPreview } from './BookCover';

const FIELDS = { title: '书名', author: '作者', publisher: '出版社', publishYear: '出版年', description: '内容简介' } as const;
type Field = keyof typeof FIELDS;
type Value = string | number | null | undefined;
const STAGES: Record<string, string> = { METADATA: '书目', COVER: '封面' };
const PROVIDERS: Record<string, string> = { OPEN_LIBRARY: 'Open Library', COVERS: '封面服务', ARCHIVE: '封面归档服务' };
const PROXY: Record<string, string> = { RESPONDED: '代理已响应', FAILED: '代理连接失败' };
/** One concise line from sanitized diagnostics; no status is invented when no upstream response arrived. */
function diagnosis(details: OpenLibraryDiagnostic | Record<string, unknown> | null | undefined): string | undefined {
  if (!details || typeof details.stage !== 'string') return undefined;
  const parts = [STAGES[details.stage] ?? details.stage];
  if (typeof details.provider === 'string') parts.push(PROVIDERS[details.provider] ?? details.provider);
  parts.push(typeof details.upstreamStatus === 'number' ? `上游 HTTP ${details.upstreamStatus}` : '未收到上游响应');
  if (details.lastRoute === 'SOCKS5') parts.push(`经代理${typeof details.proxyOutcome === 'string' && PROXY[details.proxyOutcome] ? `（${PROXY[details.proxyOutcome]}）` : ''}`);
  else if (details.lastRoute === 'DIRECT') parts.push('直连');
  return parts.join(' · ');
}
export function OpenLibraryCandidatePanel({ form, disabled, isLocked, draft, onApply }: {
  form: FormInstance; disabled: boolean; isLocked: () => boolean; draft: CoverDraft;
  onApply: (isbn: string, hasMetadata: boolean, cover?: CoverDraft) => void;
}) {
  const rawIsbn = Form.useWatch('isbn', form) as string | undefined;
  const isbn = normalizeIsbn(rawIsbn ?? '');
  const requestId = useRef(0);
  const abort = useRef<AbortController | undefined>(undefined);
  const [loading, setLoading] = useState(false);
  const [result, setResult] = useState<OpenLibraryLookup>();
  const [remoteCover, setRemoteCover] = useState<CoverDraft>();
  const [selected, setSelected] = useState<Partial<Record<Field, { value: Value }>>>({});
  const [coverSelection, setCoverSelection] = useState<{ draft: CoverDraft }>();
  const [error, setError] = useState<{ title: string; detail?: string }>();
  const [notice, setNotice] = useState<string>();
  const [focused, setFocused] = useState<string>();
  const cancel = () => { requestId.current++; abort.current?.abort(); };
  useEffect(() => {
    requestId.current++; abort.current?.abort(); setLoading(false); setResult(undefined); setRemoteCover(undefined); setError(undefined); setNotice(undefined); setSelected({}); setCoverSelection(undefined);
  }, [isbn, disabled]);
  useEffect(() => () => { requestId.current++; abort.current?.abort(); }, []);

  async function lookup() {
    if (isLocked() || !isValidIsbn(isbn)) return;
    cancel();
    const id = requestId.current;
    const controller = new AbortController(); abort.current = controller;
    setLoading(true); setResult(undefined); setRemoteCover(undefined); setError(undefined); setNotice(undefined); setSelected({}); setCoverSelection(undefined);
    const timer = setTimeout(() => controller.abort(), 16000);
    const current = () => id === requestId.current && !isLocked() && normalizeIsbn(form.getFieldValue('isbn') ?? '') === isbn;
    try {
      const response = await lookupOpenLibrary(isbn, controller.signal);
      if (!current() || controller.signal.aborted || response.isbn !== isbn) return;
      let cover: CoverDraft | undefined;
      if (response.cover) {
        try { cover = await replacementFromBase64(response.cover, isbn); }
        catch { if (current()) setNotice('候选封面无法验证，可继续手动选择本地封面'); }
      }
      if (!current() || controller.signal.aborted) return;
      const values = form.getFieldsValue();
      const defaults: Partial<Record<Field, { value: Value }>> = {};
      for (const field of Object.keys(FIELDS) as Field[]) {
        if ((values[field] == null || String(values[field]).trim() === '') && response.fields[field] != null && String(response.fields[field]).trim() !== '') defaults[field] = { value: values[field] };
      }
      setSelected(defaults); setRemoteCover(cover); setResult(response);
    } catch (failure) {
      if (!current()) return;
      if (controller.signal.aborted) setError({ title: '书目查询超时，可继续手动录入或手动重试' });
      else {
        const code = failure instanceof ApiError ? failure.code : 'NETWORK_ERROR';
        const details = failure instanceof ApiError ? failure.details : {};
        const seconds = details.retryAfterSeconds;
        const wait = details.longWait === true ? '；上游要求长时间等待，请稍后再手动重试'
          : typeof seconds === 'number' ? `；请等待 ${seconds} 秒后手动重试` : '';
        setError({ title: errorMessage(code) + wait, detail: diagnosis(details) });
      }
    } finally { clearTimeout(timer); if (id === requestId.current) setLoading(false); }
  }
  function apply() {
    if (!result || isLocked() || normalizeIsbn(form.getFieldValue('isbn') ?? '') !== result.isbn) return;
    const values = form.getFieldsValue();
    const changed = (Object.keys(selected) as Field[]).filter((field) => selected[field]?.value !== values[field]);
    if (changed.length || coverSelection && coverSelection.draft !== draft) {
      setSelected((previous) => { const next = { ...previous }; changed.forEach((field) => delete next[field]); return next; });
      if (coverSelection?.draft !== draft) setCoverSelection(undefined);
      setNotice('选中字段或封面已变化，请重新勾选后应用'); return;
    }
    const update: Partial<Record<Field, Value>> = {};
    for (const field of Object.keys(selected) as Field[]) if (result.fields[field] != null) update[field] = result.fields[field];
    form.setFieldsValue(update);
    onApply(result.isbn, Object.keys(update).length > 0, coverSelection ? remoteCover : undefined);
    cancel(); setResult(undefined); setRemoteCover(undefined); setNotice('已应用到本页草稿，保存后生效');
  }
  const currentValues = Form.useWatch([], form) ?? form.getFieldsValue();
  return <section aria-label="ISBN 书目候选" style={{ margin: '0 0 24px', padding: 16, border: '1px solid var(--claude-hairline)', borderRadius: 'var(--claude-radius-lg)', overflowWrap: 'anywhere' }}>
    <Space wrap>
      <Button disabled={disabled || !isValidIsbn(isbn)} loading={loading} onClick={lookup}>获取书目与封面</Button>
      {loading && <Button disabled={disabled} onClick={() => { cancel(); setLoading(false); }}>取消查询</Button>}
    </Space>
    <Typography.Paragraph type="secondary" style={{ marginTop: 12 }}>仅查询候选，不会保存。请比较并明确应用所选项；已有内容和本地待选封面不会自动覆盖，分类仍由您选择。</Typography.Paragraph>
    {error && <Alert type="error" title={error.title} description={error.detail} showIcon />}
    {notice && <Typography.Paragraph role="status">{notice}</Typography.Paragraph>}
    {result && <div>
      <Typography.Paragraph>ISBN {result.isbn} · <a href={result.sourceUrl} target="_blank" rel="noreferrer">Open Library 来源</a></Typography.Paragraph>
      {!result.found && <Alert type="info" title="未找到书目，可继续手动录入" showIcon />}
      {(Object.keys(FIELDS) as Field[]).map((field) => <div key={field} style={{ padding: '12px 0', borderBottom: '1px solid var(--claude-hairline)' }}>
        <Checkbox aria-label={`应用${FIELDS[field]}`} onFocus={() => setFocused(field)} onBlur={() => setFocused(undefined)} style={{ outline: focused === field ? '2px solid var(--claude-link)' : undefined, outlineOffset: 2 }} disabled={disabled || result.fields[field] == null || String(result.fields[field]).trim() === ''} checked={!!selected[field]} onChange={(event) => setSelected((previous) => {
          const next = { ...previous }; if (event.target.checked) next[field] = { value: form.getFieldValue(field) }; else delete next[field]; return next;
        })}>应用{FIELDS[field]}</Checkbox>
        <div style={{ marginTop: 8, whiteSpace: 'pre-wrap' }}>当前：{currentValues[field] || '（空）'}</div>
        <div style={{ marginTop: 4, whiteSpace: 'pre-wrap' }}>候选：<span>{result.fields[field] ?? '（未提供）'}</span></div>
      </div>)}
      <Flex gap={16} wrap style={{ marginTop: 16 }}>
        {remoteCover?.action === 'REPLACE' && <CoverPreview blob={remoteCover.blob} title="候选封面" size="lg" />}
        <div><Checkbox aria-label="应用候选封面" onFocus={() => setFocused('cover')} onBlur={() => setFocused(undefined)} style={{ outline: focused === 'cover' ? '2px solid var(--claude-link)' : undefined, outlineOffset: 2 }} disabled={disabled || !remoteCover} checked={!!coverSelection} onChange={(event) => setCoverSelection(event.target.checked ? { draft } : undefined)}>应用候选封面</Checkbox>
          <Typography.Paragraph>{result.cover ? remoteCover ? '默认不选；勾选后才替换当前封面草稿' : '候选封面无法验证' : result.warnings.some((warning) => warning.field === 'cover') ? '封面暂不可用，请查看下方提示' : '未提供封面'}</Typography.Paragraph></div>
      </Flex>
      {result.warnings.map((warning, index) => <Alert key={index} type="warning" showIcon title={warning.message} description={diagnosis(warning.details)} style={{ marginBottom: 8 }} />)}
      {result.retryAfterSeconds != null && <Typography.Paragraph>请等待 {result.retryAfterSeconds} 秒后手动重试</Typography.Paragraph>}
      {result.retryAfterSeconds == null && result.warnings.some((warning) => warning.details?.longWait === true) && <Typography.Paragraph>上游要求长时间等待，请稍后再手动重试</Typography.Paragraph>}
      <Space wrap style={{ marginTop: 16 }}><Button disabled={disabled || Object.keys(selected).length === 0 && !coverSelection} onClick={apply}>应用选中项</Button><Button disabled={disabled} onClick={() => { cancel(); setResult(undefined); setRemoteCover(undefined); setNotice(undefined); }}>取消候选</Button></Space>
    </div>}
  </section>;
}
