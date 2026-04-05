#!/bin/bash

# Codly Web 打包脚本
# 功能：Maven 打包 JAR + 压缩 web 目录

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 获取项目根目录
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET_DIR="$PROJECT_ROOT/target"
WEB_DIR="$PROJECT_ROOT/web"

# 包名配置
JAR_NAME="codly-1.0-SNAPSHOT.jar"
RELEASE_ZIP_NAME="codly-release.zip"

print_info "=========================================="
print_info "  Codly 打包脚本"
print_info "=========================================="
echo ""

# Step 1: Maven 打包
print_info "Step 1: Maven 打包..."
cd "$PROJECT_ROOT"

if command -v mvn &> /dev/null; then
    mvn clean package -DskipTests
else
    print_error "未找到 Maven，请先安装"
    exit 1
fi

if [ ! -f "$TARGET_DIR/$JAR_NAME" ]; then
    print_error "打包失败，未找到 JAR 包"
    exit 1
fi

print_info "JAR 包已生成：$TARGET_DIR/$JAR_NAME"
echo ""

# Step 2: 创建临时打包目录
print_info "Step 2: 准备打包..."
TEMP_DIR=$(mktemp -d)

# 复制 web 目录
cp -r "$WEB_DIR" "$TEMP_DIR/"
print_info "已复制 web 目录"

# 复制 JAR 包到 web 目录
cp "$TARGET_DIR/$JAR_NAME" "$TEMP_DIR/web/codly.jar"
print_info "已复制 codly.jar 到 web/"

# 复制安装脚本到 web 目录
if [ -f "$PROJECT_ROOT/install.sh" ]; then
    cp "$PROJECT_ROOT/install.sh" "$TEMP_DIR/web/"
    print_info "已复制 install.sh 到 web/"
fi
echo ""

# Step 3: 打包发布版本
print_info "Step 3: 打包发布版本..."
cd "$TEMP_DIR"
rm -f "$TARGET_DIR/$RELEASE_ZIP_NAME"
zip -r "$TARGET_DIR/$RELEASE_ZIP_NAME" . -x "*.DS_Store"
print_info "发布包已生成：$TARGET_DIR/$RELEASE_ZIP_NAME"

# 清理临时目录
rm -rf "$TEMP_DIR"
echo ""

# 完成
print_info "=========================================="
print_info "  打包完成!"
print_info "=========================================="
echo ""
echo "  发布包：$TARGET_DIR/$RELEASE_ZIP_NAME"
echo ""