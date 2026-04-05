const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = process.env.PORT || 8080;

const MIME_TYPES = {
    '.html': 'text/html; charset=utf-8',
    '.js': 'application/javascript',
    '.css': 'text/css',
    '.json': 'application/json',
    '.png': 'image/png',
    '.jpg': 'image/jpeg',
    '.jpeg': 'image/jpeg',
    '.gif': 'image/gif',
    '.webp': 'image/webp',
    '.ico': 'image/x-icon',
    '.svg': 'image/svg+xml',
    '.jar': 'application/java-archive',
    '.sh': 'application/x-sh'
};

const server = http.createServer((req, res) => {
    // 设置 CORS
    res.setHeader('Access-Control-Allow-Origin', '*');
    res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
    res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

    if (req.method === 'OPTIONS') {
        res.writeHead(204);
        res.end();
        return;
    }

    // favicon.ico 返回 404
    if (req.url === '/favicon.ico') {
        res.writeHead(404);
        res.end('Not found');
        return;
    }

    const url = new URL(req.url, `http://${req.headers.host}`);
    const pathname = url.pathname;

    // JAR 包下载
    if (pathname === '/download/jar' || pathname === '/download/codly.jar') {
        const jarPath = path.join(__dirname, 'codly.jar');
        if (fs.existsSync(jarPath)) {
            res.writeHead(200, {
                'Content-Type': 'application/java-archive',
                'Content-Disposition': 'attachment; filename="codly.jar"',
                'Cache-Control': 'no-cache'
            });
            const stream = fs.createReadStream(jarPath);
            stream.pipe(res);
        } else {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Jar file not found' }));
        }
        return;
    }

    // 安装脚本下载
    if (pathname === '/download/install.sh' || pathname === '/install.sh') {
        const scriptPath = path.join(__dirname, 'install.sh');
        if (fs.existsSync(scriptPath)) {
            res.writeHead(200, {
                'Content-Type': 'application/x-sh',
                'Content-Disposition': 'attachment; filename="install.sh"',
                'Cache-Control': 'no-cache'
            });
            const stream = fs.createReadStream(scriptPath);
            stream.pipe(res);
        } else {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'Install script not found' }));
        }
        return;
    }

    // 根路径和 Codly 页面
    if (pathname === '/' || pathname === '/codly' || pathname === '/codly.html') {
        const filePath = path.join(__dirname, 'codly.html');
        fs.readFile(filePath, (err, content) => {
            if (err) {
                res.writeHead(404);
                res.end('File not found');
            } else {
                res.writeHead(200, { 'Content-Type': MIME_TYPES['.html'] });
                res.end(content);
            }
        });
        return;
    }

    // 静态文件服务
    const filePath = path.join(__dirname, pathname);
    const ext = path.extname(filePath);
    const contentType = MIME_TYPES[ext] || 'application/octet-stream';

    fs.readFile(filePath, (err, content) => {
        if (err) {
            if (err.code === 'ENOENT') {
                res.writeHead(404);
                res.end('File not found');
            } else {
                res.writeHead(500);
                res.end('Server error');
            }
        } else {
            res.writeHead(200, { 'Content-Type': contentType });
            res.end(content);
        }
    });
});

server.listen(PORT, () => {
    console.log(`Codly Server running at http://localhost:${PORT}`);
});
