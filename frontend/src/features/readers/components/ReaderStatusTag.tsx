// 读者状态 Tag：ACTIVE=启用 / DISABLED=停用（reader.status 是唯一事实来源，第 6 节）。
import { Tag } from 'antd';
import type { ReaderStatus } from '../../../api/types';

export function ReaderStatusTag({ status }: { status: ReaderStatus }) {
  return status === 'ACTIVE' ? <Tag color="success">启用</Tag> : <Tag color="error">停用</Tag>;
}