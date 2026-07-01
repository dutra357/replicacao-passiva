#!/bin/bash

# Aborta com falha
set -e

ENGINE="docker"
COMPOSE_CMD="docker compose"

if command -v podman >/dev/null 2>&1; then
    ENGINE="podman"

    if command -v podman-compose >/dev/null 2>&1; then
        COMPOSE_CMD="podman-compose"
    else
        COMPOSE_CMD="podman compose"
    fi
fi

echo "🚀 Iniciando ambiente com: $ENGINE ($COMPOSE_CMD)"

echo "📦 Compilando imagem do Servidor..."
$ENGINE build -t rep-server:latest ./rep-server

echo "📦 Compilando imagem do Cliente..."
$ENGINE build -t rep-client:latest ./rep-client

echo "🚀 Subindo o cluster..."

$COMPOSE_CMD down
$COMPOSE_CMD up -d

echo "⏳ Aguardando inicialização das aplicações..."
sleep 8

SESSION="dist-lock-cluster"

# Remove sessão anterior
set +e
tmux has-session -t "$SESSION" 2>/dev/null
if [ $? -eq 0 ]; then
    tmux kill-session -t "$SESSION"
fi
set -e

echo "🖥️ Abrindo painel de monitoramento..."

# Cria sessão vazia
tmux new-session -d -s "$SESSION"

tmux split-window -h -p 50


# Coluna esquerda (Clientes)

tmux select-pane -t 0
tmux split-window -v -p 66
tmux split-window -v -p 50


# Coluna direita (Servidores)

tmux select-pane -t 3
tmux split-window -v -p 66
tmux split-window -v -p 50

# Clientes
tmux send-keys -t 0 \
"clear; echo '=========== CLIENTE 1 ==========='; $ENGINE logs -f client-1" C-m

tmux send-keys -t 1 \
"clear; echo '=========== CLIENTE 2 ==========='; $ENGINE logs -f client-2" C-m

tmux send-keys -t 2 \
"clear; echo '=========== CLIENTE 3 ==========='; $ENGINE logs -f client-3" C-m

# Servidores
tmux send-keys -t 3 \
"clear; echo '=========== SERVIDOR 1 ==========='; $ENGINE logs -f server-1" C-m

tmux send-keys -t 4 \
"clear; echo '=========== SERVIDOR 2 ==========='; $ENGINE logs -f server-2" C-m

tmux send-keys -t 5 \
"clear; echo '=========== SERVIDOR 3 ==========='; $ENGINE logs -f server-3" C-m

# Deixa o foco no primeiro cliente
tmux select-pane -t 0

echo "📺 Abrindo painel de logs..."

# Anexa à sessão
if [ -n "$TMUX" ]; then
    tmux switch-client -t "$SESSION"
else
    tmux attach-session -t "$SESSION"
fi