# OUC 智慧食堂 —— 部署指南

本项目为 **Spring Boot 单体应用**，前后端同端口运行，已容器化为一键部署方案。

## 一、部署架构

```
云服务器
├── ouc-app     Spring Boot 2.7 (JDK 8)   端口 8080 → 对外暴露
├── ouc-mysql   MySQL 8.0                 仅内网访问
└── ouc-redis   Redis 7                   仅内网访问

数据卷（容器重建不丢数据）：
├── mysql-data    数据库数据
├── redis-data    缓存/共享购物车数据
└── upload-data   上传的菜品图片、音频文件
```

## 二、服务器要求

| 项目 | 最低配置 | 推荐配置 |
|---|---|---|
| CPU / 内存 | 2 核 2G | 2 核 4G |
| 系统盘 | 40 GB | 60 GB |
| 系统 | Ubuntu 20.04+ / Debian 11+ / CentOS 7+ | Ubuntu 22.04 |

> MySQL + Redis + Java 应用同机运行，**2G 内存是下限**，若构建时内存不足，可先在本地构建镜像再推送。
> 参考价格：学生机约 ¥9-10/月，新用户活动机约 ¥99/年。

## 三、部署步骤

### 1. 购买服务器并放通端口

在云服务商控制台的**安全组 / 防火墙**中放通：

- `22`（SSH，用于登录）
- `8080`（应用访问端口）

> 若想用 80 端口直接访问，可把 `APP_PORT` 改为 `80`，但需确保有权限绑定。

### 2. 上传项目到服务器

在**本地电脑**执行（把项目传到服务器）：

```bash
# 方式一：scp 直接上传（排除无关文件）
scp -r D:/java项目/OUC-Take-Out root@服务器IP:/root/

# 方式二：若代码已在 Git 仓库，直接在服务器上克隆
git clone 你的仓库地址 /root/OUC-Take-Out
```

进入项目目录：

```bash
cd /root/OUC-Take-Out
```

### 3. 配置环境变量

```bash
cp .env.example .env
vim .env
```

**必须修改的项：**

| 变量 | 说明 | 示例 |
|---|---|---|
| `APP_DOMAIN` | 对外访问地址，**桌台二维码会写入此地址** | `http://123.45.67.89` |
| `MYSQL_PASSWORD` | 数据库密码，改成强密码 | `Reggie@Mysql2026` |
| `REDIS_PASSWORD` | Redis 密码，改成强密码 | `Reggie@Redis2026` |
| `REMEMBER_KEY` | Cookie 签名密钥，≥32 位随机串 | `openssl rand -hex 32` 生成 |
| `MAIL_USER` | 发验证码的 QQ 邮箱 | `2771464100@qq.com` |
| `MAIL_PASSWORD` | QQ 邮箱 SMTP 授权码（非登录密码） | 邮箱设置中获取 |

生成随机密钥：

```bash
openssl rand -hex 32
```

### 4. 一键部署

```bash
bash deploy.sh
```

脚本会自动完成：安装 Docker（若无）→ 构建镜像 → 启动 MySQL/Redis/应用 → 等待就绪 → 输出访问地址。

首次构建需下载 Maven 依赖，约 3-8 分钟。

### 5. 访问验证

- **顾客点餐端**：`http://服务器IP:8080/front/index.html`
- **管理后台**：`http://服务器IP:8080/backend/index.html`
- **默认管理员**：`admin` / `123456` ← **登录后请立即修改密码**

## 四、常用运维命令

```bash
# 查看服务状态
docker compose ps

# 查看应用日志（实时）
docker compose logs -f app

# 查看数据库日志
docker compose logs -f mysql

# 重启应用
docker compose restart app

# 更新代码后重新部署
docker compose up -d --build app

# 停止全部服务（数据保留在数据卷中）
docker compose down

# 完全清除（含数据，谨慎使用）
docker compose down -v
```

## 五、数据备份

```bash
# 备份数据库到当前目录
docker exec ouc-mysql mysqldump -uroot -p你的密码 reggie > backup_$(date +%Y%m%d).sql

# 恢复数据库
docker exec -i ouc-mysql mysql -uroot -p你的密码 reggie < backup_20260924.sql

# 备份上传的图片文件
docker run --rm -v ouc-take-out_upload-data:/data -v $(pwd):/backup \
  alpine tar czf /backup/upload_$(date +%Y%m%d).tar.gz -C /data .
```

## 六、常见问题

### 应用启动失败，日志显示连不上数据库
MySQL 首次启动需初始化数据，健康检查通过后应用才会启动。查看进度：

```bash
docker compose logs -f mysql
```

### 手机扫码桌台二维码打不开
`.env` 里的 `APP_DOMAIN` 必须填**公网可访问地址**，不能是 `127.0.0.1`。改完执行：

```bash
docker compose up -d --force-recreate app
```

### 内存不足导致构建失败
在本地构建好镜像后推送到服务器，或临时增大 swap：

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile
mkswap /swapfile && swapon /swapfile
```

### 邮箱验证码收不到
确认 `.env` 中 `MAIL_PASSWORD` 填的是 **SMTP 授权码**（在 QQ 邮箱「设置 → 账户 → POP3/SMTP服务」中开启后获取），不是 QQ 登录密码。

## 七、上线前安全检查清单

- [ ] `.env` 中的 `MYSQL_PASSWORD`、`REDIS_PASSWORD` 已改为强密码
- [ ] `REMEMBER_KEY` 已改为随机生成的 32 位以上字符串
- [ ] 管理员默认密码 `123456` 已修改
- [ ] `APP_DOMAIN` 已配置为真实公网地址
- [ ] `.env` 文件**未提交到 Git 仓库**（已在 `.gitignore` 中排除）
- [ ] 云服务器安全组仅放通必要端口，MySQL(3306)/Redis(6379) **不对公网开放**

## 八、已验证项（本地实测结论）

部署包中的配置已通过本地真实环境（MySQL 8.0.45 + Redis）验证：

| 验证项 | 结果 |
|---|---|
| Maven 构建产出 jar | ✅ 78 MB，前端页面已打包进 jar |
| 应用启动 | ✅ 4.2 秒启动成功 |
| C 端首页 `/front/index.html` | ✅ HTTP 200，35 KB |
| 管理员登录 `/employee/login` | ✅ 返回 code:1，Session Cookie 正常 |
| 登录拦截 | ✅ 未登录访问返回 `NOTLOGIN`（安全机制生效）|
| 数据库查询 `/category/list` | ✅ 返回「川菜/粤菜/湘菜」 |
| 分页查询 `/dish/page` | ✅ 返回「麻婆豆腐 ¥12.00」，关联字段正常 |
| 桌台列表 `/dinnerTable/list` | ✅ 返回 A01/A02 等 18 张桌台 |
| 二维码生成 `/dinnerTable/qrCode/1` | ✅ 返回 base64 PNG，域名取自 `APP_DOMAIN` |
| Redis 连接 | ✅ 启动无报错，缓存写入正常 |

**注意事项：**

1. **Redis 密码变量不可留空**。`.env` 中 `REDIS_PASSWORD=`（空值）会导致应用启动时端口异常，务必赋值。
2. 二维码接口路径是 `/dinnerTable/qrCode/{id}`（大写 C），不是 `qrcode`。
3. 桌台二维码内容取自 `APP_DOMAIN`，部署后若域名变更，需重新生成二维码。

