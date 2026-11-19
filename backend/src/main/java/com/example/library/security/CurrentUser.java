package com.example.library.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 控制器参数注解：注入当前登录的 {@link LibraryUserPrincipal}。
 * 读者身份只从 principal 取得（防 IDOR，design-v0.1 第 7 节）。未登录时抛 401 AUTH_REQUIRED。
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}