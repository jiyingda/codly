#!/bin/bash

# Codly 一键安装脚本
# 支持 macOS 和 Linux

set -e

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 配置
INSTALL_DIR="$HOME/.codly"
# TODO: 填写远程 JAR 包下载地址
JAR_URL="http://codly.jiyingda.com/download/codly.jar"
JAR_NAME="codly.jar"
CONFIG_FILE="$INSTALL_DIR/settings.json"

# 打印带颜色的消息
print_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

print_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# 检查 Java 环境
check_java() {
    if command -v java &> /dev/null; then
        JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
        print_info "检测到 Java 版本：$JAVA_VERSION"
        if [ "$JAVA_VERSION" -lt 17 ]; then
            print_error "Java 版本过低，需要 Java 17 或更高版本"
            exit 1
        fi
    else
        print_error "未检测到 Java 环境，请先安装 Java 17 或更高版本"
        echo "macOS: brew install openjdk@17"
        echo "Ubuntu: sudo apt install openjdk-17-jdk"
        exit 1
    fi
}

# 创建安装目录
create_install_dir() {
    if [ ! -d "$INSTALL_DIR" ]; then
        mkdir -p "$INSTALL_DIR"
        print_info "创建安装目录：$INSTALL_DIR"
    fi
}

# 下载 JAR 包
download_jar() {
    if [ -z "$JAR_URL" ]; then
        print_warn "JAR_URL 未配置，跳过下载"
        print_warn "请手动将 JAR 包复制到：$INSTALL_DIR/codly.jar"
        return 0
    fi

    if [ -f "$INSTALL_DIR/codly.jar" ]; then
        print_warn "JAR 包已存在，是否重新下载？"
        if [ -t 0 ]; then
            read -p "(y/N): " confirm
        else
            read -p "(y/N): " confirm < /dev/tty
        fi
        if [ "$confirm" != "y" ]; then
            print_info "使用现有 JAR 包"
            return 0
        fi
    fi

    print_info "正在下载 JAR 包..."

    if command -v curl &> /dev/null; then
        curl -L -o "$INSTALL_DIR/codly.jar" "$JAR_URL"
    elif command -v wget &> /dev/null; then
        wget -O "$INSTALL_DIR/codly.jar" "$JAR_URL"
    else
        print_error "未找到 curl 或 wget，请手动下载 JAR 包到：$INSTALL_DIR/codly.jar"
        exit 1
    fi

    if [ -f "$INSTALL_DIR/codly.jar" ] && [ -s "$INSTALL_DIR/codly.jar" ]; then
        print_info "JAR 包已下载：$INSTALL_DIR/codly.jar"
    else
        print_error "下载失败，请检查 JAR_URL 是否正确"
        exit 1
    fi

    return 0
}

# 创建配置文件
create_config() {
    if [ -f "$CONFIG_FILE" ]; then
        print_warn "配置文件已存在：$CONFIG_FILE"
        if [ -t 0 ]; then
            read -p "是否覆盖？(y/N): " confirm
        else
            read -p "是否覆盖？(y/N): " confirm < /dev/tty
        fi
        if [ "$confirm" != "y" ]; then
            print_info "跳过配置文件创建"
            return 0
        fi
    fi

    cat > "$CONFIG_FILE" << 'EOF'
{
  "apiKey": "your-api-key-here",
  "apiUrl": "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
  "enableThinking": true,
  "defaultModel": "qwen3.5-plus",
  "availableModels": [
    "qwen3.5-plus",
    "qwen3-max-2026-01-23",
    "qwen3-coder-next",
    "qwen3-coder-plus",
    "glm-5",
    "glm-4.7",
    "kimi-k2.5",
    "MiniMax-M2.5"
  ]
}
EOF
    print_info "配置文件已创建：$CONFIG_FILE"
    print_warn "请编辑配置文件，填入您的 API Key"
}

# 配置环境变量
setup_env() {
    SHELL_PROFILE=""
    EXPORT_LINE="export PATH=\"$INSTALL_DIR:\$PATH\""

    # 检测 shell 类型
    if [ -n "$ZSH_VERSION" ] || [ -f "$HOME/.zshrc" ]; then
        SHELL_PROFILE="$HOME/.zshrc"
    elif [ -n "$BASH_VERSION" ] || [ -f "$HOME/.bashrc" ]; then
        SHELL_PROFILE="$HOME/.bashrc"
    elif [ -f "$HOME/.profile" ]; then
        SHELL_PROFILE="$HOME/.profile"
    fi

    if [ -n "$SHELL_PROFILE" ]; then
        # 检查是否已配置 Codly 路径
        if ! grep -q "$INSTALL_DIR" "$SHELL_PROFILE" 2>/dev/null; then
            echo "" >> "$SHELL_PROFILE"
            echo "# Codly" >> "$SHELL_PROFILE"
            echo "$EXPORT_LINE" >> "$SHELL_PROFILE"
            print_info "已将 Codly 添加到 PATH ($SHELL_PROFILE)"
        else
            print_warn "Codly 已在 PATH 中配置"
        fi

        # 创建启动脚本
        cat > "$INSTALL_DIR/codly" << 'EOF'
#!/bin/bash
java -jar "$(dirname "$0")/codly.jar" "$@"
EOF
        chmod +x "$INSTALL_DIR/codly"

        # 立即生效（当前 shell 会话）
        export PATH="$INSTALL_DIR:$PATH"
    else
        print_warn "未找到 shell 配置文件，请手动添加以下行到 ~/.zshrc 或 ~/.bashrc:"
        echo "  export PATH=\"$INSTALL_DIR:\$PATH\""
    fi
}

# 打印完成信息
print_finish() {
    echo ""
    print_info "=========================================="
    print_info "Codly 安装完成!"
    print_info "=========================================="
    echo ""
    echo "  安装目录：$INSTALL_DIR"
    echo "  JAR 包位置：$INSTALL_DIR/codly.jar"
    echo "  配置文件：$CONFIG_FILE"
    echo ""
    echo "  下一步:"
    echo "  1. 编辑配置文件，填入您的 API Key:"
    echo "     $EDITOR $CONFIG_FILE"
    echo ""
    echo "  2. 启动 Codly:"
    echo "     codly"
    echo ""
    echo "  3. 查看帮助:"
    echo "     codly /help"
    echo ""
}

# 主函数
main() {
    echo ""
    echo "=========================================="
    echo "  Codly 一键安装脚本"
    echo "=========================================="
    echo ""

    check_java
    create_install_dir
    download_jar
    create_config
    setup_env
    print_finish
}

# 运行
main "$@"