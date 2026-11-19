/**
 * catalog 模块（分类、图书、副本与检索 API）：T04 实现。
 * 契约：design-v0.1 第 6/7/8 节；AC-CAT-01、AC-BOOK-01/02/03、AC-COPY-01/02、
 * AC-SRCH-01/02（后端部分）、AC-AUTH-04（本模块授权用例）。
 * 借还逻辑属于 loan 模块（T06），本模块只读 LoanRepository 做删除前历史检查。
 */
package com.example.library.catalog;