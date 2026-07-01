package com.dutra.rep_server.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.LinkedList;

@Service
public class LockService {

    private final ConcurrentHashMap<String, String> locks = new ConcurrentHashMap<>();


    private final ConcurrentHashMap<String, Queue<String>> waitQueue = new ConcurrentHashMap<>();

    private final String serverId = System.getenv("SERVER_ID");

    public boolean tryLock(String resourceId, String clientId) {
        if (locks.putIfAbsent(resourceId, clientId) == null) {
            System.out.printf("✅ [%s] LOCK DEFERIDO para '%s' (Cliente: %s)%n", serverId, resourceId, clientId);
            return true;
        }

        System.out.printf("⛔ [%s] LOCK NEGADO para '%s'. Em uso por outro cliente.%n", serverId, resourceId);
        waitQueue.computeIfAbsent(resourceId, k -> new LinkedList<>()).add(clientId);
        System.out.printf("⏳ [%s] Cliente %s adicionado à fila do recurso '%s'%n", serverId, clientId, resourceId);
        return false;
    }

    public void unlock(String resourceId, String clientId) {
        if (clientId.equals(locks.get(resourceId))) {
            locks.remove(resourceId);
            System.out.printf("🔓 [%s] LOCK LIBERADO de '%s' pelo cliente %s%n", serverId, resourceId, clientId);
            // Lógica para notificar/processar a fila entraria aqui
        } else {
            System.out.printf("❌ [%s] TENTATIVA INVÁLIDA: %s tentou liberar lock de terceiros em '%s'%n", serverId, clientId, resourceId);
        }
    }

    // Método para sincronizar o estado vindo do líder (Replicação Passiva)
    public void syncState(String resourceId, String clientId) {
        locks.put(resourceId, clientId);
        System.out.printf("🔄 [%s] BACKUP SINCRONIZADO: recurso '%s' -> %s%n", serverId, resourceId, clientId);
    }
}