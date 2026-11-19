#!/usr/bin/env bash
# env.sh — 设置并校验开发环境（设计第 5 节命令约定）。
#
# 用法：
#   source scripts/env.sh
#   LIB_JAVA_HOME=/path/to/jdk21 source scripts/env.sh   # 覆盖默认 JDK
#
# 行为：
#   - JAVA_HOME 默认 /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home，
#     可用 LIB_JAVA_HOME 覆盖。
#   - PATH 前置 $JAVA_HOME/bin 和 /opt/homebrew/bin（避开已损坏的 nvm 懒加载）。
#   - 校验 Java 主版本 >= 21、Node >= 20.19；失败时输出指引并返回非 0。
#   - 被 source 时失败只返回非 0，不会退出调用方 shell；直接 bash 执行时以非 0 退出。
#
# 兼容 macOS 自带 bash 3.2；不加载 zsh/nvm，不修改任何全局配置。

_lib_env_err() { printf '%s\n' "$*" >&2; }

# 从 java 版本串解析主版本号："1.8.0_421" -> 8；"21.0.12" -> 21；"21-ea" -> 21。
_lib_env_java_major() {
  local v="$1"
  case "$v" in
    1.*) v="${v#1.}"; printf '%s\n' "${v%%.*}" ;;
    *)   printf '%s\n' "${v%%[.-]*}" ;;
  esac
}

# 校验 node 版本串（vX.Y.Z）是否 >= 20.19。返回 0 表示满足。
_lib_env_node_ok() {
  local v="${1#v}" major minor
  major="${v%%.*}"
  minor="${v#*.}"
  minor="${minor%%.*}"
  case "$major" in ''|*[!0-9]*) return 1 ;; esac
  case "$minor" in ''|*[!0-9]*) return 1 ;; esac
  if [ "$major" -gt 20 ]; then return 0; fi
  if [ "$major" -eq 20 ] && [ "$minor" -ge 19 ]; then return 0; fi
  return 1
}

_lib_env_main() {
  JAVA_HOME="${LIB_JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
  export JAVA_HOME
  export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:$PATH"

  # --- Java >= 21 ---
  if [ ! -x "$JAVA_HOME/bin/java" ]; then
    _lib_env_err "[env.sh] 错误：JAVA_HOME=$JAVA_HOME 下没有可执行的 bin/java。"
    _lib_env_err "[env.sh] 指引：安装 Homebrew JDK 21（brew install openjdk@21），"
    _lib_env_err "[env.sh]       或设置 LIB_JAVA_HOME 指向一个 JDK 21+ 的 Home 目录后重试。"
    return 1
  fi
  local jline jver jmajor
  jline=$("$JAVA_HOME/bin/java" -version 2>&1 | head -n 1)
  jver=$(printf '%s\n' "$jline" | sed -n 's/.*version "\([^"]*\)".*/\1/p')
  jmajor=$(_lib_env_java_major "$jver")
  case "$jmajor" in
    ''|*[!0-9]*)
      _lib_env_err "[env.sh] 错误：无法解析 Java 版本号（输出：${jline}）。"
      _lib_env_err "[env.sh] 指引：确认 LIB_JAVA_HOME 指向有效的 JDK 21+ 安装。"
      return 1
      ;;
  esac
  if [ "$jmajor" -lt 21 ]; then
    _lib_env_err "[env.sh] 错误：需要 Java 21 及以上，当前 JAVA_HOME=${JAVA_HOME} 是 Java ${jmajor}（${jver}）。"
    _lib_env_err "[env.sh] 指引：默认 java 是 1.8，本项目不使用它。请安装 Homebrew JDK 21"
    _lib_env_err "[env.sh]       （brew install openjdk@21），或 LIB_JAVA_HOME=<JDK21 Home> 重新 source。"
    return 1
  fi

  # --- Node >= 20.19（使用 /opt/homebrew/bin，不经过 nvm）---
  if ! command -v node >/dev/null 2>&1; then
    _lib_env_err "[env.sh] 错误：PATH 中找不到 node。"
    _lib_env_err "[env.sh] 指引：安装 Homebrew Node（brew install node），脚本使用 /opt/homebrew/bin 下的 node/npm。"
    return 1
  fi
  local nver
  if ! nver=$(node --version 2>&1); then
    _lib_env_err "[env.sh] 错误：node --version 执行失败：$nver"
    _lib_env_err "[env.sh] 指引：请确认 /opt/homebrew/bin/node 可用；交互式 zsh 的 nvm 懒加载已损坏，不要依赖它。"
    return 1
  fi
  if ! _lib_env_node_ok "$nver"; then
    _lib_env_err "[env.sh] 错误：需要 Node >= 20.19，当前 ${nver}（$(command -v node)）。"
    _lib_env_err "[env.sh] 指引：升级 Homebrew Node（brew upgrade node），脚本使用 /opt/homebrew/bin 下的 node/npm。"
    return 1
  fi
  if ! command -v npm >/dev/null 2>&1; then
    _lib_env_err "[env.sh] 错误：PATH 中找不到 npm。"
    _lib_env_err "[env.sh] 指引：安装 Homebrew Node（brew install node）会自带 npm；不要使用损坏的 nvm。"
    return 1
  fi

  return 0
}

_lib_env_main
_lib_env_rc=$?
unset -f _lib_env_main
if [ "$_lib_env_rc" -ne 0 ]; then
  unset _lib_env_rc
  return 1 2>/dev/null || exit 1
fi
unset _lib_env_rc
return 0 2>/dev/null || exit 0