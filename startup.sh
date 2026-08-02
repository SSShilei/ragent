#!/bin/bash
# Ragent 一键启动脚本
# 用法: ./startup.sh

set -e

# ============================================
# 环境变量配置（填写你的 API Key）
# ============================================
# Gitee AI - Embedding + Rerank（必需）
export GITEE_API_KEY="YOUR_GITEE_API_KEY"

# DeepSeek 聊天模型（必需）
export DEEPSEEK_API_KEY="YOUR_DEEPSEEK_API_KEY"

# 阿里云百炼 - 聊天模型降级备选（可选，deepseek 挂了才走）
export BAILIAN_API_KEY=""

# MinerU - PDF/Word/PPT 文档解析（可选）
export MINERU_API_KEY=""

# ============================================
PROJECT_ROOT="/home/shilei/IdeaProjects/ragent"

echo "=========================================="
echo "  Ragent AI 基础设施启动"
echo "=========================================="

# 1. 检查 PostgreSQL
echo "[1/4] 检查 PostgreSQL..."
if ! pg_isready -h 127.0.0.1 -p 5433 -q; then
    echo "  PostgreSQL 未启动，启动中..."
    sudo systemctl start postgresql
    sleep 2
else
    echo "  PostgreSQL 已运行"
fi

# 2. 检查 Redis
echo "[2/4] 检查 Redis..."
if ! redis-cli -a 123456 ping > /dev/null 2>&1; then
    echo "  Redis 未启动或密码错误，启动中..."
    sudo systemctl start redis-server
    sleep 2
    if ! redis-cli -a 123456 ping > /dev/null 2>&1; then
        echo "  [错误] Redis 连接失败，请检查密码配置"
        exit 1
    fi
else
    echo "  Redis 已运行"
fi

# 3. 启动 RocketMQ (Docker)
echo "[3/4] 检查 RocketMQ..."
if ! docker ps --format "{{.Names}}" | grep -q "rmqnamesrv"; then
    echo "  启动 RocketMQ..."
    docker compose -f "${PROJECT_ROOT}/resources/docker/rocketmq-stack-5.2.0.compose.yaml" up -d
else
    echo "  RocketMQ 已运行"
fi

# 4. 启动 RustFS (Docker)
echo "[4/4] 检查 RustFS..."
if ! docker ps --format "{{.Names}}" | grep -q "^rustfs$"; then
    echo "  启动 RustFS..."
    docker run -d \
        --name rustfs \
        -p 9000:9000 -p 9001:9001 \
        -e RUSTFS_ACCESS_KEY=rustfsadmin \
        -e RUSTFS_SECRET_KEY=rustfsadmin \
        -v rustfs-data:/data \
        --restart unless-stopped \
        rustfs/rustfs:1.0.0-alpha.72 \
        --address ":9000" --console-enable \
        --access-key rustfsadmin --secret-key rustfsadmin /data
else
    echo "  RustFS 已运行"
fi

# 5. 提示启动后端
echo "=========================================="
echo "  基础设施就绪"
echo "=========================================="
echo "  PostgreSQL : 127.0.0.1:5433"
echo "  Redis      : 127.0.0.1:6379 (密码: 123456)"
echo "  RocketMQ   : localhost:9876"
echo "  RustFS     : localhost:9000"
echo ""
echo "  请在 IDEA 中启动 RagentApplication"
echo "  然后手动启动前端: cd frontend && npm run dev"
echo "=========================================="