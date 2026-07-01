#!/bin/sh

echo "⏳ START_DELAY=${START_DELAY:-0}s antes de iniciar aplicação..."
sleep "${START_DELAY:-0}"

echo "🚀 Iniciando aplicação..."

exec "$@"