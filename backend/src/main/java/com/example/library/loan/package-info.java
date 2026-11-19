/**
 * loan 模块（T06 已实现）：借/还/续借接口（design-v0.1 第 8.4/9 节）、
 * LoanPolicy 纯函数（第 9.6 节）、LoanViewMapper（renewable 叠加调用者语境）。
 * 并发正确性依赖 Hikari 单连接 + 条件 UPDATE + ux_loan_active_copy 部分唯一索引（第 9.5 节）。
 */
package com.example.library.loan;