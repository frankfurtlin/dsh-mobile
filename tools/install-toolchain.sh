#!/usr/bin/env bash
# install-toolchain.sh — 在用户空间安装 Android 构建工具链（JDK 21 + Android cmdline-tools）。
#
# 设计约束：
#   * 全部装在 $ROOT（默认 ${HOME}/.local/android）下，不写 /usr、不调用 apt，
#     以适配 workspace-write 沙箱策略。
#   * 幂等：已存在的组件跳过下载/解压。
#   * 可通过环境变量覆盖版本与镜像：
#       DSH_MOBILE_TOOLCHAIN=/path       安装根目录
#       JDK_URL=... / CMDLINE_URL=...    替换下载地址
#
# 用法： bash tools/install-toolchain.sh [--skip-sdk-packages]
set -euo pipefail

ROOT="${DSH_MOBILE_TOOLCHAIN:-${HOME}/.local/android}"
JDK_URL="${JDK_URL:-https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/linux/OpenJDK21U-jdk_x64_linux_hotspot_21.0.12.1_1.tar.gz}"
CMDLINE_URL="${CMDLINE_URL:-https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip}"

SDK_PLATFORM="${SDK_PLATFORM:-android-35}"
SDK_BUILD_TOOLS="${SDK_BUILD_TOOLS:-35.0.0}"

SKIP_SDK_PACKAGES=0
[ "${1:-}" = "--skip-sdk-packages" ] && SKIP_SDK_PACKAGES=1

log() { printf '\033[1;36m==>\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

command -v curl >/dev/null || die "curl 未安装"
command -v unzip >/dev/null || die "unzip 未安装"
command -v tar >/dev/null || die "tar 未安装"

mkdir -p "$ROOT" "$ROOT/downloads"

# ── 1. JDK 21 ────────────────────────────────────────────────────────────────
JDK_DIR="$(find "$ROOT" -maxdepth 1 -type d -name 'jdk-21*' | head -1 || true)"
if [ -z "$JDK_DIR" ]; then
  log "下载 JDK 21：$JDK_URL"
  curl -fL --retry 3 --retry-delay 2 -o "$ROOT/downloads/jdk21.tar.gz" "$JDK_URL" \
    || die "JDK 下载失败；可用 JDK_URL=... 指定其它镜像"
  log "解压 JDK"
  tar -xzf "$ROOT/downloads/jdk21.tar.gz" -C "$ROOT"
  JDK_DIR="$(find "$ROOT" -maxdepth 1 -type d -name 'jdk-21*' | head -1)"
  [ -n "$JDK_DIR" ] || die "JDK 解压后未找到 jdk-21* 目录"
else
  log "JDK 已存在：$JDK_DIR"
fi
export JAVA_HOME="$JDK_DIR"
export PATH="$JAVA_HOME/bin:$PATH"
log "Java 版本：$(java -version 2>&1 | head -1)"

# ── 2. Android cmdline-tools ─────────────────────────────────────────────────
CMDLINE_ROOT="$ROOT/cmdline-tools"
if [ ! -x "$CMDLINE_ROOT/latest/bin/sdkmanager" ]; then
  log "下载 Android cmdline-tools：$CMDLINE_URL"
  curl -fL --retry 3 --retry-delay 2 -o "$ROOT/downloads/cmdline-tools.zip" "$CMDLINE_URL" \
    || die "cmdline-tools 下载失败"
  log "解压 cmdline-tools"
  rm -rf "$CMDLINE_ROOT/latest.tmp"
  mkdir -p "$CMDLINE_ROOT/latest.tmp"
  unzip -q "$ROOT/downloads/cmdline-tools.zip" -d "$CMDLINE_ROOT/latest.tmp"
  # zip 内层目录名为 cmdline-tools；sdkmanager 要求路径为 <sdk>/cmdline-tools/latest/bin
  rm -rf "$CMDLINE_ROOT/latest"
  mv "$CMDLINE_ROOT/latest.tmp/cmdline-tools" "$CMDLINE_ROOT/latest"
  rm -rf "$CMDLINE_ROOT/latest.tmp"
else
  log "cmdline-tools 已存在：$CMDLINE_ROOT/latest"
fi
SDKMANAGER="$CMDLINE_ROOT/latest/bin/sdkmanager"
[ -x "$SDKMANAGER" ] || die "sdkmanager 不可执行：$SDKMANAGER"

# ── 3. SDK 组件（platform-tools / platform / build-tools） ───────────────────
if [ "$SKIP_SDK_PACKAGES" -eq 0 ]; then
  export ANDROID_HOME="$ROOT"
  export ANDROID_SDK_ROOT="$ROOT"
  log "接受 SDK 许可协议"
  yes | "$SDKMANAGER" --sdk_root="$ROOT" --licenses >/dev/null 2>&1 || true
  log "安装 platform-tools / platforms;$SDK_PLATFORM / build-tools;$SDK_BUILD_TOOLS"
  "$SDKMANAGER" --sdk_root="$ROOT" "platform-tools" "platforms;$SDK_PLATFORM" "build-tools;$SDK_BUILD_TOOLS" \
    || die "SDK 组件安装失败（网络或许可问题）"
else
  log "按要求跳过 SDK 组件安装"
fi

# ── 4. 生成 env.sh（后续脚本 source 它） ─────────────────────────────────────
cat > "$ROOT/env.sh" <<EOF
# 由 tools/install-toolchain.sh 生成——source 后再构建
export JAVA_HOME="$JDK_DIR"
export ANDROID_HOME="$ROOT"
export ANDROID_SDK_ROOT="$ROOT"
export PATH="\$JAVA_HOME/bin:\$ANDROID_HOME/platform-tools:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/build-tools/35.0.0:\$PATH"
EOF

log "完成。工具链根目录：$ROOT"
log "用法： source $ROOT/env.sh"
"$SDKMANAGER" --sdk_root="$ROOT" --version 2>/dev/null | tail -1 || true
