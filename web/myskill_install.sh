#!/usr/bin/env bash
# myskill 安装脚本（macOS / Linux）。
#
# 自动按当前机器的 OS / 架构挑选合适的二进制安装,支持以下产物命名:
#   myskill-darwin-arm64   macOS Apple Silicon
#   myskill-darwin-x64     macOS Intel
#   myskill-linux-arm64    Linux ARM64
#   myskill-linux-x64      Linux x86_64
#
# 用法 1（裸管道,按当前平台从默认源下载）:
#   curl -fsSL http://codly.jiyingda.com/myskill_install.sh | bash
#
# 用法 2（本地已有对应平台二进制,在当前目录或 ./dist/）:
#   bash myskill_install.sh
#
# 用法 3（自定义下载源）:
#   bash myskill_install.sh --url-template='https://example.com/myskill-{OS}-{ARCH}'
#   bash myskill_install.sh --url='https://example.com/myskill-darwin-arm64'
#
# 安装位置: 默认 ~/.local/bin/myskill；带 --system 装到 /usr/local/bin/myskill (需要 sudo)。

set -euo pipefail

INSTALL_DIR="$HOME/.local/bin"
USE_SUDO=""
URL=""
# 默认按平台拼 URL 的模板,可用 --url / --url-template 覆盖
URL_TEMPLATE="http://codly.jiyingda.com/download/myskill-{OS}-{ARCH}"

for arg in "$@"; do
  case "$arg" in
    --system)         INSTALL_DIR="/usr/local/bin"; USE_SUDO="sudo" ;;
    --url=*)          URL="${arg#--url=}" ;;
    --url-template=*) URL_TEMPLATE="${arg#--url-template=}" ;;
    -h|--help)
      cat <<'EOF'
myskill 安装脚本 (macOS / Linux)

用法:
  curl -fsSL http://codly.jiyingda.com/myskill_install.sh | bash   # 裸管道,按平台自动下载
  bash myskill_install.sh                                          # 当前目录/dist 有平台二进制则直接装
  bash myskill_install.sh --system                                 # 装到 /usr/local/bin (需 sudo)
  bash myskill_install.sh --url-template='https://x.com/myskill-{OS}-{ARCH}'
  bash myskill_install.sh --url='https://x.com/myskill-darwin-arm64'

选项:
  --system                 装到系统级 /usr/local/bin (需 sudo)
  --url=<URL>              直接指定完整下载 URL (不会按平台切换)
  --url-template=<TPL>     URL 模板,支持 {OS} {ARCH} 占位符
  -h, --help               显示帮助

支持的平台:
  darwin-arm64   macOS Apple Silicon
  darwin-x64     macOS Intel
  linux-arm64    Linux ARM64
  linux-x64      Linux x86_64
EOF
      exit 0
      ;;
  esac
done

# 1. 检测 OS / arch
case "$(uname -s)" in
  Darwin) OS="darwin" ;;
  Linux)  OS="linux" ;;
  *) echo "✗ 不支持的操作系统: $(uname -s)（Windows 请直接下载 myskill-windows-x64.exe 手动放到 PATH）"; exit 1 ;;
esac
case "$(uname -m)" in
  arm64|aarch64) ARCH="arm64" ;;
  x86_64|amd64)  ARCH="x64" ;;
  *) echo "✗ 不支持的 CPU 架构: $(uname -m)"; exit 1 ;;
esac
TARGET="myskill-${OS}-${ARCH}"
echo "▶ 当前平台: ${OS}-${ARCH}（将选用 ${TARGET}）"

# 2. 找/下载二进制
# 管道执行时 $0 = bash,cd dirname 会失败,统一兜底到 pwd
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" 2>/dev/null && pwd || pwd)"
SOURCE_BINARY=""

# 源选择优先级: --url > 本地同名文件 > --url-template / 默认模板
if [ -n "$URL" ]; then
  TMP_DIR="$(mktemp -d)"; trap 'rm -rf "$TMP_DIR"' EXIT
  echo "▶ 下载 $URL"
  curl -fSL "$URL" -o "$TMP_DIR/myskill"
  SOURCE_BINARY="$TMP_DIR/myskill"
else
  # 本地优先
  for cand in \
      "$SCRIPT_DIR/$TARGET" \
      "$SCRIPT_DIR/dist/$TARGET" \
      "./$TARGET" \
      "./dist/$TARGET" \
      "$SCRIPT_DIR/myskill" \
      "./myskill"; do
    if [ -f "$cand" ]; then SOURCE_BINARY="$cand"; break; fi
  done

  # 本地没找到则走模板下载
  if [ -z "$SOURCE_BINARY" ]; then
    if [ -z "$URL_TEMPLATE" ]; then
      echo "✗ 未找到本地二进制,且未指定下载地址"
      echo "  请把 $TARGET 放到当前目录 / dist/,或使用 --url / --url-template。"
      exit 1
    fi
    RESOLVED="${URL_TEMPLATE//\{OS\}/$OS}"
    RESOLVED="${RESOLVED//\{ARCH\}/$ARCH}"
    TMP_DIR="$(mktemp -d)"; trap 'rm -rf "$TMP_DIR"' EXIT
    echo "▶ 下载 $RESOLVED"
    curl -fSL "$RESOLVED" -o "$TMP_DIR/myskill"
    SOURCE_BINARY="$TMP_DIR/myskill"
  fi
fi

if [ ! -f "$SOURCE_BINARY" ]; then
  echo "✗ 找不到二进制: $SOURCE_BINARY"
  exit 1
fi

echo "▶ 安装到 $INSTALL_DIR/myskill (来源: $SOURCE_BINARY)"
$USE_SUDO mkdir -p "$INSTALL_DIR"
$USE_SUDO install -m 0755 "$SOURCE_BINARY" "$INSTALL_DIR/myskill"

# macOS: 去掉隔离标记,避免 Gatekeeper 拦截
if [ "$OS" = "darwin" ]; then
  $USE_SUDO xattr -d com.apple.quarantine "$INSTALL_DIR/myskill" 2>/dev/null || true
fi

# 3. 检查 PATH
case ":$PATH:" in
  *":$INSTALL_DIR:"*) IN_PATH=1 ;;
  *) IN_PATH=0 ;;
esac

echo "✓ 已安装: $INSTALL_DIR/myskill"
echo

if [ "$IN_PATH" = "0" ]; then
  echo "⚠ $INSTALL_DIR 不在你当前的 PATH 里。请把下面这行追加到 ~/.zshrc 或 ~/.bashrc:"
  echo
  echo "    export PATH=\"$INSTALL_DIR:\$PATH\""
  echo
  echo "然后执行: source ~/.zshrc"
else
  echo "→ 现在可以直接运行: myskill"
fi
