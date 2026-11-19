package com.example.library.common.config;

import java.sql.SQLException;
import java.sql.Types;

import org.hibernate.JDBCException;
import org.hibernate.community.dialect.SQLiteDialect;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.spi.SQLExceptionConversionDelegate;

/**
 * SQLite 方言兼容层（T01；design-v0.1 K6 要求 ddl-auto=validate 通过）。
 *
 * 背景 1：Hibernate 7.4 的 schema 校验（ColumnDefinitions.hasMatchingType）用
 * Dialect.equivalentTypes 比较 JDBC 类型码；默认实现不认为 BIGINT 与 INTEGER 等价，
 * 而 SQLite 所有整数列（含 AUTOINCREMENT 主键、epoch 毫秒、0/1 布尔）的元数据都是 INTEGER，
 * 导致 Long 映射（BIGINT）校验失败。本类放宽“整数族等价”（符合 SQLite INTEGER 亲和性）。
 *
 * 背景 2：xerial sqlite-jdbc 的约束冲突 SQLException 不带标准 SQLState，
 * Hibernate 默认把唯一/外键冲突翻译成通用 JDBCException（Spring 侧变成 JpaSystemException），
 * 而设计第 9.2/9.5 节要求服务层捕获 DataIntegrityViolationException 转 409。
 * 本类补充转换委托：SQLITE_CONSTRAINT* → ConstraintViolationException
 * （Spring 再翻译为 DataIntegrityViolationException）。
 *
 * 其余行为完全继承 org.hibernate.community.dialect.SQLiteDialect。
 * 与方案第 5 节字面配置的偏差已在 T01 交接报告中披露。
 */
public class LibrarySQLiteDialect extends SQLiteDialect {

    @Override
    public boolean equivalentTypes(int typeCode1, int typeCode2) {
        if (super.equivalentTypes(typeCode1, typeCode2)) {
            return true;
        }
        return isIntegerFamily(typeCode1) && isIntegerFamily(typeCode2);
    }

    private static boolean isIntegerFamily(int typeCode) {
        return typeCode == Types.TINYINT || typeCode == Types.SMALLINT
                || typeCode == Types.INTEGER || typeCode == Types.BIGINT;
    }

    @Override
    public SQLExceptionConversionDelegate buildSQLExceptionConversionDelegate() {
        SQLExceptionConversionDelegate inherited = super.buildSQLExceptionConversionDelegate();
        return (sqlException, message, sql) -> {
            JDBCException converted = inherited == null ? null : inherited.convert(sqlException, message, sql);
            if (converted != null) {
                return converted;
            }
            return convertSqliteConstraint(sqlException, message);
        };
    }

    private static JDBCException convertSqliteConstraint(SQLException sqlException, String message) {
        String detail = sqlException.getMessage();
        if (detail == null || !detail.contains("SQLITE_CONSTRAINT")) {
            return null;
        }
        ConstraintViolationException.ConstraintKind kind =
                detail.contains("FOREIGN KEY")
                        ? ConstraintViolationException.ConstraintKind.FOREIGN_KEY
                        : detail.contains("UNIQUE")
                                ? ConstraintViolationException.ConstraintKind.UNIQUE
                                : ConstraintViolationException.ConstraintKind.OTHER;
        return new ConstraintViolationException(message, sqlException, kind, null);
    }
}