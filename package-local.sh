#!/bin/bash

# Codly 本地打包部署脚本
# 功能：Maven 打包 JAR，并替换 ~/.codly/codly.jar，供本地快速测试
#
# 用法：
#   ./package-local.sh             打包并替换（默认保留上一版本为 codly.jar.bak）
#   ./package-local.sh --no-backup 打包并替换，不保留备份
#   ./package-local.sh -h          查看帮助
#
# 说明：
# - 自动探测可用的 JDK（>= 17），不依赖当前 JAVA_HOME 是否指向 JDK
# - 只有打包成功且校验通过才会替换，打包失败时原有 JAR 保持可用

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

print_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
print_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
print_error() { echo -e "${RED}[ERROR]${NC} $1"; }
print_step()  { echo -e "${CYAN}==>${NC} $1"; }

BACKUP=1

usage() {
    cat <<'EOF'
Codly 本地打包部署脚本

用法：
  ./package-local.sh              打包并替换 ~/.codly/codly.jar
  ./package-local.sh --no-backup  替换时不保留上一版本备份
  ./package-local.sh -h           显示本帮助
EOF
}

for arg in "$@"; do
    case "$arg" in
        -h|--help)
            usage
            exit 0
            ;;
        --no-backup)
            BACKUP=0
            ;;
        *)
            print_error "未知参数：$arg"
            usage
            exit 1
            ;;
    esac
done

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="$PROJECT_ROOT/target"
JAR_NAME="codly-1.0-SNAPSHOT.jar"
BUILT_JAR="$TARGET_DIR/$JAR_NAME"
BUILD_LOG="$TARGET_DIR/build.log"

INSTALL_DIR="$HOME/.codly"
INSTALL_JAR="$INSTALL_DIR/codly.jar"

# ---------- JDK 探测 ----------

# 输出 JDK 主版本号，失败返回非 0
jdk_major() {
    local javac="$1/bin/javac"
    if [ ! -x "$javac" ]; then
        return 1
    fi
    local raw
    raw=$("$javac" -version 2>&1 | awk '{print $2}')
    if [ -z "$raw" ]; then
        return 1
    fi
    case "$raw" in
        1.*) raw=$(echo "$raw" | cut -d. -f2) ;;
        *)   raw=${raw%%.*} ;;
    esac
    echo "$raw"
}

# 按优先级列出候选 JDK 目录
collect_jdk_candidates() {
    if [ -n "${JAVA_HOME:-}" ]; then
        printf '%s\n' "$JAVA_HOME"
    fi

    if [ -x /usr/libexec/java_home ]; then
        for v in 17 21 22 23 24 25; do
            /usr/libexec/java_home -v "$v" 2>/dev/null || true
        done
    fi

    local g
    for g in /Library/Java/JavaVirtualMachines/*/Contents/Home \
             "$HOME"/Library/Java/JavaVirtualMachines/*/Contents/Home \
             /opt/homebrew/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
             /usr/local/opt/openjdk*/libexec/openjdk.jdk/Contents/Home \
             /usr/lib/jvm/*; do
        if [ -d "$g" ]; then
            printf '%s\n' "$g"
        fi
    done
}

find_jdk() {
    local dir major
    while IFS= read -r dir; do
        if [ -z "$dir" ] || [ ! -d "$dir" ]; then
            continue
        fi
        major=$(jdk_major "$dir" 2>/dev/null || true)
        if [ -z "$major" ]; then
            continue
        fi
        if [ "$major" -ge 17 ] 2>/dev/null; then
            printf '%s\n' "$dir"
            return 0
        fi
    done <<EOF
$(collect_jdk_candidates)
EOF
    return 1
}

# 从 java 可执行文件解析主版本号，失败返回非 0
java_major() {
    local bin="$1"
    if [ ! -x "$bin" ]; then
        return 1
    fi
    local raw
    raw=$("$bin" -version 2>&1 | head -n 1 | sed -n 's/.*version "\([^"]*\)".*/\1/p')
    if [ -z "$raw" ]; then
        return 1
    fi
    case "$raw" in
        1.*) raw=$(echo "$raw" | cut -d. -f2) ;;
        *)   raw=${raw%%.*} ;;
    esac
    echo "$raw"
}

# ---------- 前置检查 ----------

if ! command -v mvn > /dev/null 2>&1; then
    print_error "未找到 Maven，请先安装（macOS: brew install maven）"
    exit 1
fi

echo ""
print_step "Codly 本地打包"
echo ""

JDK_HOME=$(find_jdk || true)
if [ -z "$JDK_HOME" ]; then
    print_error "未找到 JDK 17 或更高版本"
    echo "  JAVA_HOME 当前为：${JAVA_HOME:-<未设置>}"
    echo "  macOS:  brew install openjdk@17"
    echo "  Ubuntu: sudo apt install openjdk-17-jdk"
    exit 1
fi

JDK_VERSION=$(jdk_major "$JDK_HOME")
print_info "JDK：${JDK_HOME}（Java ${JDK_VERSION}）"
if [ -n "${JAVA_HOME:-}" ] && [ "${JAVA_HOME}" != "$JDK_HOME" ]; then
    print_warn "已忽略 JAVA_HOME（${JAVA_HOME}），本次打包使用上面的 JDK"
fi

# ---------- 打包 ----------

print_step "Maven 打包中（日志：${BUILD_LOG}）"
mkdir -p "$TARGET_DIR"

if ! JAVA_HOME="$JDK_HOME" mvn -f "$PROJECT_ROOT/pom.xml" clean package -DskipTests > "$BUILD_LOG" 2>&1; then
    print_error "打包失败，最后 30 行日志："
    echo "----------------------------------------"
    tail -n 30 "$BUILD_LOG"
    echo "----------------------------------------"
    print_error "未替换 ${INSTALL_JAR}，现有 JAR 不受影响"
    exit 1
fi

if [ ! -f "$BUILT_JAR" ]; then
    print_error "打包命令成功但未生成 ${BUILT_JAR}"
    exit 1
fi

# ---------- 校验产物 ----------

MANIFEST=$(unzip -p "$BUILT_JAR" META-INF/MANIFEST.MF 2>/dev/null || true)
case "$MANIFEST" in
    *"Main-Class: com.jiyingda.codly.CodlyMain"*) ;;
    *)
        print_error "${JAR_NAME} 缺少 Main-Class，可能是未 shade 的原始包，已中止"
        exit 1
        ;;
esac

ENTRY_COUNT=$(unzip -l "$BUILT_JAR" | grep -c 'com/jiyingda/codly/CodlyMain.class' || true)
if [ "$ENTRY_COUNT" -lt 1 ]; then
    print_error "${JAR_NAME} 中未找到 CodlyMain.class，已中止"
    exit 1
fi

print_info "产物校验通过：$(ls -lh "$BUILT_JAR" | awk '{print $5}')"

# ---------- 替换 ----------

print_step "替换 ${INSTALL_JAR}"
mkdir -p "$INSTALL_DIR"

if [ -f "$INSTALL_JAR" ]; then
    OLD_INFO=$(ls -lh "$INSTALL_JAR" | awk '{print $5", "$6" "$7" "$8}')
    if [ "$BACKUP" -eq 1 ]; then
        cp -p "$INSTALL_JAR" "$INSTALL_JAR.bak"
        print_info "上一版本已备份：${INSTALL_JAR}.bak（${OLD_INFO}）"
    else
        print_info "覆盖上一版本（${OLD_INFO}）"
    fi
else
    print_info "本地尚无 codly.jar，直接安装"
fi

TMP_JAR="$INSTALL_JAR.tmp.$$"
trap 'rm -f "$TMP_JAR"' EXIT
cp "$BUILT_JAR" "$TMP_JAR"
mv -f "$TMP_JAR" "$INSTALL_JAR"
trap - EXIT

NEW_INFO=$(ls -lh "$INSTALL_JAR" | awk '{print $5", "$6" "$7" "$8}')
print_info "已更新：${INSTALL_JAR}（${NEW_INFO}）"

echo ""
print_step "完成"
echo ""

# 运行命令直接用上面探到的 JDK，避免 PATH 上的 java 版本过低导致启动失败
PATH_JAVA=$(command -v java 2>/dev/null || true)
PATH_JAVA_MAJOR=""
if [ -n "$PATH_JAVA" ]; then
    PATH_JAVA_MAJOR=$(java_major "$PATH_JAVA" 2>/dev/null || true)
fi

RUN_JAVA_OK=0
if [ -n "$PATH_JAVA_MAJOR" ] && [ "$PATH_JAVA_MAJOR" -ge 17 ] 2>/dev/null; then
    RUN_JAVA_OK=1
fi

echo "  运行方式："
if [ "$RUN_JAVA_OK" -eq 1 ] && [ -x "$INSTALL_DIR/codly" ]; then
    echo "    codly"
elif [ "$RUN_JAVA_OK" -eq 1 ]; then
    echo "    java -jar ${INSTALL_JAR}"
else
    echo "    ${JDK_HOME}/bin/java -jar ${INSTALL_JAR}"
    echo ""
    print_warn "PATH 上的 java 是 Java ${PATH_JAVA_MAJOR:-未知}（${PATH_JAVA:-未找到}），低于运行所需的 Java 17"
    print_warn "直接执行 codly 会报 UnsupportedClassVersionError，请用上面的完整命令"
    print_warn "或把 JDK 17+ 的 bin 目录放到 PATH 中 java 的前面"
fi

if [ "$BACKUP" -eq 1 ] && [ -f "$INSTALL_JAR.bak" ]; then
    echo ""
    echo "  回滚上一版本："
    echo "    mv ${INSTALL_JAR}.bak ${INSTALL_JAR}"
fi
echo ""
