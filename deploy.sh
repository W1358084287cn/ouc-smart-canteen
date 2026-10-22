#!/bin/bash
# ============================================
# OUC 智慧食堂 —— 一键部署脚本
# 适用：Ubuntu / Debian / CentOS 云服务器（已安装 Docker）
# 使用：bash deploy.sh
# ============================================
set -e

GREEN='\033[0;32m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
error() { echo -e "${RED}[ERROR]${NC} $1"; }

echo "=========================================="
echo "  OUC 智慧食堂 一键部署"
echo "=========================================="

# ---------- 1. 环境检查 ----------
if ! command -v docker >/dev/null 2>&1; then
    error "未检测到 Docker，正在安装..."
    curl -fsSL https://get.docker.com | sh
    systemctl enable docker
    systemctl start docker
    info "Docker 安装完成"
else
    info "Docker 已安装：$(docker --version)"
fi

# 检查 docker compose 插件
if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
elif command -v docker-compose >/dev/null 2>&1; then
    COMPOSE_CMD="docker-compose"
else
    error "未检测到 docker compose，请先安装 docker-compose-plugin"
    exit 1
fi
info "Compose 命令：$COMPOSE_CMD"

# ---------- 2. 配置文件准备 ----------
if [ ! -f .env ]; then
    if [ -f .env.example ]; then
        cp .env.example .env
        warn "已从 .env.example 生成 .env"
        warn "请编辑 .env 填写数据库密码、APP_DOMAIN、邮箱授权码后重新执行本脚本"
        echo ""
        echo "  编辑命令：  vim .env"
        echo "  必填项：    APP_DOMAIN / MYSQL_PASSWORD / REDIS_PASSWORD / REMEMBER_KEY"
        echo ""
        exit 0
    else
        error "缺少 .env 与 .env.example 文件"
        exit 1
    fi
fi
info "已加载 .env 配置"

# 读取 APP_DOMAIN 用于最终提示
APP_DOMAIN=$(grep -E '^APP_DOMAIN=' .env | cut -d '=' -f2- | tr -d '"' | tr -d "'")
APP_PORT=$(grep -E '^APP_PORT=' .env | cut -d '=' -f2- | tr -d '"' | tr -d "'")
APP_PORT=${APP_PORT:-8080}

# 安全提醒：未修改默认密码时告警
if grep -q "ChangeMe" .env; then
    warn "检测到 .env 中仍存在默认密码（ChangeMe），生产环境存在安全风险，建议立即修改"
fi

# ---------- 3. 构建并启动 ----------
info "开始构建镜像（首次构建需下载依赖，约 3-8 分钟）..."
$COMPOSE_CMD build

info "启动服务..."
$COMPOSE_CMD up -d

# ---------- 4. 等待应用就绪 ----------
info "等待服务启动..."
MAX_WAIT=180
WAITED=0
while [ $WAITED -lt $MAX_WAIT ]; do
    if curl -fs "http://localhost:${APP_PORT}/front/index.html" >/dev/null 2>&1; then
        info "应用已就绪"
        break
    fi
    sleep 5
    WAITED=$((WAITED + 5))
    echo -n "."
done
echo ""

# ---------- 5. 结果输出 ----------
echo ""
echo "=========================================="
if curl -fs "http://localhost:${APP_PORT}/front/index.html" >/dev/null 2>&1; then
    echo -e "${GREEN}  部署成功！${NC}"
    echo "=========================================="
    echo ""
    echo "  顾客点餐端（手机扫码打开）："
    echo "    ${APP_DOMAIN}/front/index.html"
    echo ""
    echo "  管理后台："
    echo "    ${APP_DOMAIN}/backend/index.html"
    echo "    默认账号：admin / 123456（请登录后立即修改）"
    echo ""
    echo "  数据卷位置："
    echo "    MySQL 数据   docker volume inspect ouc-take-out_mysql-data"
    echo "    Redis 数据   docker volume inspect ouc-take-out_redis-data"
    echo "    上传文件     docker volume inspect ouc-take-out_upload-data"
    echo ""
    echo "  常用命令："
    echo "    查看日志     $COMPOSE_CMD logs -f app"
    echo "    重启应用     $COMPOSE_CMD restart app"
    echo "    停止服务     $COMPOSE_CMD down"
    echo "    更新代码后   $COMPOSE_CMD up -d --build app"
else
    echo -e "${RED}  部署可能失败，请检查日志${NC}"
    echo "=========================================="
    echo ""
    echo "  排查命令：$COMPOSE_CMD logs --tail=100 app"
fi
