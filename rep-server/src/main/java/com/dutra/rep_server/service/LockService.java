package com.dutra.rep_server.service;

import org.springframework.stereotype.Service;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.LinkedList;

@Service
public class LockService {

    // Mapa de recurso_id -> cliente_id atual
    private final ConcurrentHashMap<String, String> locks = new ConcurrentHashMap<>();
    // Fila de espera por recurso
    private final ConcurrentHashMap<String, Queue<String>> waitQueue = new ConcurrentHashMap<>();
    private final String serverId = System.getenv("SERVER_ID");

    public boolean tryLock(String resourceId, String clientId) {
        if (locks.putIfAbsent(resourceId, clientId) == null) {
            System.out.printf("✅ [%s] LOCK DEFERIDO para '%s' (Cliente: %s)%n", serverId, resourceId, clientId);

            // Remove o cliente da fila de espera, caso estivesse nela
            Queue<String> queue = waitQueue.get(resourceId);
            if (queue != null) {
                queue.remove(clientId);
            }
            return true;
        }

        System.out.printf("⛔ [%s] LOCK NEGADO para '%s'. Em uso por '%s'.%n", serverId, resourceId, locks.get(resourceId));
        waitQueue.computeIfAbsent(resourceId, k -> new LinkedList<>()).add(clientId);
        System.out.printf("⏳ [%s] Cliente %s adicionado à fila do recurso '%s'%n", serverId, clientId, resourceId);
        return false;
    }

    public boolean unlock(String resourceId, String clientId) {
        // Valida se quem está tentando liberar é realmente o dono do lock
        if (clientId.equals(locks.get(resourceId))) {
            locks.remove(resourceId);
            System.out.printf("🔓 [%s] LOCK LIBERADO de '%s' pelo cliente %s%n", serverId, resourceId, clientId);
            return true;
        } else {
            System.out.printf("❌ [%s] TENTATIVA INVÁLIDA: %s tentou liberar lock de terceiros em '%s'%n", serverId, clientId, resourceId);
            return false;
        }
    }

    // -----------------------------------------------------------------
    // Métodos para receber o estado do Primary (Replicação Passiva)
    // -----------------------------------------------------------------

    public void syncLock(String resourceId, String clientId) {
        locks.put(resourceId, clientId);
        System.out.printf("🔄 [%s] BACKUP SINCRONIZADO (LOCK): recurso '%s' -> %s%n", serverId, resourceId, clientId);
    }

    public void syncUnlock(String resourceId) {
        locks.remove(resourceId);
        System.out.printf("🔄 [%s] BACKUP SINCRONIZADO (UNLOCK): recurso '%s' liberado%n", serverId, resourceId);
    }
}