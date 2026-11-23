import { useEffect, useRef, useState } from 'react';
import { Alert, Button, Flex, Space, Typography } from 'antd';
import type { BookDetail } from '../../../api/types';
import { type CoverDraft, readCoverFile } from '../coverDraft';
import { BookCover, CoverPreview } from './BookCover';

export function CoverEditor({ book, draft, onChange, disabled, isLocked, serverError }: {
  book?: BookDetail; draft: CoverDraft; onChange: (draft: CoverDraft) => void;
  disabled: boolean; isLocked: () => boolean; serverError?: string;
}) {
  const sequence = useRef(0);
  const [error, setError] = useState<string>();
  const [reading, setReading] = useState(false);
  const [focused, setFocused] = useState(false);
  const displayedError = error ?? serverError;
  useEffect(() => () => { sequence.current++; }, []);
  useEffect(() => { if (disabled) { sequence.current++; setReading(false); } }, [disabled]);
  function change(value: CoverDraft) {
    if (isLocked()) return;
    sequence.current++; setReading(false); setError(undefined); onChange(value);
  }
  return <section aria-label="图书封面" style={{ marginBottom: 24, padding: 16, background: 'var(--claude-surface-soft)', borderRadius: 'var(--claude-radius-lg)' }}>
    <Typography.Title level={5} style={{ marginTop: 0 }}>图书封面</Typography.Title>
    <Flex gap={16} wrap align="start">
      {draft.action === 'REPLACE' ? <CoverPreview title="待保存封面" blob={draft.blob} size="lg" /> : <BookCover title={book?.title ?? '暂无封面'} author={book?.author} size="lg" bookId={book?.id} cover={draft.action === 'KEEP' ? book?.cover : null} />}
      <div style={{ flex: '1 1 220px', minWidth: 0 }}>
        <Typography.Paragraph id="cover-help">单张 JPEG / PNG，不超过 2 MiB。预览仅保留在本页，随书目保存；封面来源为录入记录，不代表外部认证。</Typography.Paragraph>
        <label htmlFor="local-cover">选择本地封面</label>
        <input id="local-cover" type="file" accept="image/jpeg,image/png" disabled={disabled} aria-describedby={displayedError ? 'cover-help cover-error' : 'cover-help'} aria-invalid={!!displayedError} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)} style={{ display: 'block', maxWidth: '100%', margin: '8px 0 16px', outline: focused ? '2px solid var(--claude-link)' : undefined, outlineOffset: 2 }} onChange={async (event) => {
          const file = event.target.files?.[0]; event.target.value = '';
          if (!file || isLocked()) return;
          const request = ++sequence.current; setReading(true); setError(undefined);
          try {
            const next = await readCoverFile(file);
            if (request === sequence.current && !isLocked()) onChange(next);
          } catch (failure) {
            if (request === sequence.current && !isLocked()) setError(failure instanceof Error ? failure.message : '图片读取失败');
          } finally { if (request === sequence.current) setReading(false); }
        }} />
        <Space wrap>
          <Button disabled={disabled} onClick={() => change({ action: 'REMOVE' })}>移除封面</Button>
          <Button disabled={disabled || draft.action === 'KEEP'} onClick={() => change({ action: 'KEEP' })}>撤销封面修改</Button>
        </Space>
        <Typography.Paragraph role="status" style={{ margin: '12px 0 0' }}>{reading ? '正在验证图片…' : draft.action === 'REMOVE' ? '待移除，保存后生效' : draft.action === 'REPLACE' ? `待保存 · ${draft.source ? 'ShowAPI 候选' : draft.sourceIsbn ? 'Open Library 候选' : '本地上传'}` : '未修改封面'}</Typography.Paragraph>
        {displayedError && <Alert id="cover-error" type="error" showIcon title={displayedError} style={{ marginTop: 12 }} />}
      </div>
    </Flex>
  </section>;
}
