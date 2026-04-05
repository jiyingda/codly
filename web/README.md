# Codly Web

Codly 官方网站和文件服务器

## 项目结构

```
.
├── index.html            # Codly 官方网站
├── static/               # 静态资源
│   ├── css/             # 样式文件
│   ├── js/              # JavaScript 文件
│   └── images/          # 图片资源
├── server.js            # Node.js 服务器
├── install.sh           # 一键安装脚本
├── codly.jar            # Codly JAR 包
└── README.md            # 项目说明
```

## 启动服务

### 方式 1：前台运行（开发调试）

```bash
node server.js
```

### 方式 2：后台运行

```bash
nohup node server.js > server.log 2>&1 &
```

### 方式 3：使用 PM2（生产环境推荐）

```bash
# 安装 PM2
npm install -g pm2

# 启动服务
pm2 start server.js --name codly

# 指定端口启动
PORT=80 pm2 start server.js --name codly

# 保存 PM2 配置（开机自启）
pm2 save
pm2 startup
```

---

## 服务管理

### 停止服务

**普通方式：**
```bash
# 查找进程
ps aux | grep "node server.js"

# 杀死进程
pkill -f "node server.js"

# 或通过端口杀死
lsof -ti :8080 | xargs kill -9
```

**PM2 方式：**
```bash
pm2 stop codly
pm2 delete codly
```

### 重启服务

**普通方式：**
```bash
pkill -f "node server.js" && node server.js
```

**PM2 方式：**
```bash
pm2 restart codly
```

### 查看状态

**普通方式：**
```bash
# 查看进程
ps aux | grep "node server.js"

# 查看端口占用
lsof -i :8080

# 查看日志
tail -f server.log
```

**PM2 方式：**
```bash
pm2 status
pm2 logs codly
```

---

## 访问地址

| 页面 | 地址 | 说明 |
|------|------|------|
| Codly 官网 | http://localhost:8080/ | 产品介绍页面 |
| JAR 包下载 | http://localhost:8080/download/codly.jar | 直接下载 |
| 安装脚本 | http://localhost:8080/install.sh | 一键安装 |

---

## 一键安装

```bash
# 下载安装脚本并运行
curl -fsSL http://localhost:8080/install.sh | bash
```

安装后：
- JAR 包位置：`~/.codly/codly.jar`
- 配置文件：`~/.codly/settings.json`
- 启动命令：`codly`

---

## 环境变量

| 变量名 | 说明 | 默认值 |
|--------|------|--------|
| `PORT` | 服务端口 | `8080` |

```bash
# 自定义端口启动
PORT=3000 node server.js
```

---

## 服务器功能

- 静态文件服务（HTML/CSS/JS/图片）
- JAR 包下载接口 `/download/codly.jar`
- 安装脚本下载 `/install.sh`

---

## 技术栈

- **前端**: HTML5, CSS3, JavaScript
- **设计风格**: 极简科技风（深色主题 + 青色点缀）
- **后端**: Node.js (http 模块)
- **Java**: 17+

---

## GitHub

[https://github.com/jiyingda/codly](https://github.com/jiyingda/codly)

## License

MIT