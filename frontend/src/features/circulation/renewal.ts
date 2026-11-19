// D2 用户决定（approval-record.md）：读者不能自助续借（reader-self-renew=false）。
// 前端按此开关隐藏"我的借阅"中的续借按钮；开关逻辑保留，后端配置改为允许时只需把这里改为 true。
// 是否真正可续借仍以服务端返回的 Loan.renewable 为准（双重条件：开关 && renewable）。
// 本文件属于 circulation feature，由 T10 在实现 MyLoansPage 时使用；T02 仅预置契约。
export const READER_SELF_RENEW_ENABLED = false;