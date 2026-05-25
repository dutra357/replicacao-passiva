#!/bin/bash

echo "🚀 Subindo a infraestrutura distribuída com Podman..."
# Derruba qualquer resquício e sobe tudo em background
podman compose down
podman compose up -d --build

# Dá uns segundos pro Spring Boot iniciar e cuspir os primeiros logs
sleep 10

SESSION="lock_demo"

# Verifica se a sessão já existe e mata para começar limpo
tmux has-session -t $SESSION 2>/dev/null
if [ $? == 0 ]; then
  tmux kill-session -t $SESSION
fi

echo "🖥️ Abrindo os terminais multiplexados..."

# Inicia uma nova sessão do tmux em background
tmux new-session -d -s $SESSION

# DIVISÃO DA TELA: 2 Colunas, 3 Linhas
# Divide a tela no meio (esquerda/direita)
tmux split-window -h -p 50

# Na coluna da esquerda (painel 0), cria 3 linhas (Servidores)
tmux select-pane -t 0
tmux split-window -v -p 66
tmux split-window -v -p 50

# Na coluna da direita (agora painel 3), cria 3 linhas (Clientes)
tmux select-pane -t 3
tmux split-window -v -p 66
tmux split-window -v -p 50

# Envia o comando de log individual para cada painel
# SERVIDORES (Esquerda)
tmux send-keys -t 0 'clear; echo "=== 👑 SERVIDOR PRIMÁRIO ==="; podman logs -f lock-server-primary' C-m
tmux send-keys -t 1 'clear; echo "=== 🛡️ SERVIDOR BACKUP 1 ==="; podman logs -f lock-server-backup1' C-m
tmux send-keys -t 2 'clear; echo "=== 🛡️ SERVIDOR BACKUP 2 ==="; podman logs -f lock-server-backup2' C-m

# CLIENTES (Direita)
tmux send-keys -t 3 'clear; echo "=== 👤 CLIENTE 1 ==="; podman logs -f client-1' C-m
tmux send-keys -t 4 'clear; echo "=== 👤 CLIENTE 2 ==="; podman logs -f client-2' C-m
tmux send-keys -t 5 'clear; echo "=== 👤 CLIENTE 3 ==="; podman logs -f client-3' C-m

# Anexa você à sessão para ver o show
tmux attach-session -t $SESSION