import type { DunningMethod, DunningStatus } from '../../api/types';

export const methodLabels: Record<DunningMethod, string> = { PHONE: '电话', IN_PERSON: '当面', IN_APP: '站内', OTHER: '其他' };
export const statusLabels: Record<DunningStatus, string> = { OPEN: '未结案', CLOSED: '已结案' };
const formatter = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' });
export function formatDunningTime(timestamp: string): string { return formatter.format(new Date(timestamp)); }
