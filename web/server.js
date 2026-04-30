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

    // myskill 安装脚本下载(用于 curl | bash 场景,所以用 inline 返回)
    if (pathname === '/download/myskill_install.sh' || pathname === '/myskill_install.sh') {
        const scriptPath = path.join(__dirname, 'myskill_install.sh');
        if (fs.existsSync(scriptPath)) {
            res.writeHead(200, {
                'Content-Type': 'application/x-sh',
                'Cache-Control': 'no-cache'
            });
            const stream = fs.createReadStream(scriptPath);
            stream.pipe(res);
        } else {
            res.writeHead(404, { 'Content-Type': 'application/json' });
            res.end(JSON.stringify({ error: 'myskill install script not found' }));
        }
        return;
    }

    // myskill 二进制下载:支持 /download/myskill 及 /download/myskill-<os>-<arch>
    // 允许的文件名白名单,防止路径穿越
    {
        const myskillMatch = pathname.match(/^\/download\/(myskill(?:-[a-z0-9]+-[a-z0-9]+)?(?:\.exe)?)$/);
        if (myskillMatch) {
            const fileName = myskillMatch[1];
            const binaryPath = path.join(__dirname, fileName);
            if (fs.existsSync(binaryPath)) {
                res.writeHead(200, {
                    'Content-Type': 'application/octet-stream',
                    'Content-Disposition': `attachment; filename="${fileName}"`,
                    'Cache-Control': 'no-cache'
                });
                const stream = fs.createReadStream(binaryPath);
                stream.pipe(res);
            } else {
                res.writeHead(404, { 'Content-Type': 'application/json' });
                res.end(JSON.stringify({ error: `${fileName} not found` }));
            }
            return;
        }
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

    // myskill 页面
    if (pathname === '/myskill' || pathname === '/myskill.html') {
        const filePath = path.join(__dirname, 'myskill.html');
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
