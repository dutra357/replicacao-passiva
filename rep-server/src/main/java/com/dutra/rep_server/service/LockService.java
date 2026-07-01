package com.dutra.rep_server.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.LinkedList;

@Service
public class LockService {

    private final ConcurrentHashMap<String, LockInfo> locks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Queue<String>> waitQueue = new ConcurrentHashMap<>();

    private final String serverId = System.getenv("SERVER_ID");

    private final long LEASE_DURATION_MS = 10000;

    private volatile boolean crashed = false;


    public boolean isCrashed() { return crashed; }

    public void setCrashed(boolean crashed) { this.crashed = crashed; }

    public Map<String, String> exportState() {
        Map<String, String> snapshot = new java.util.HashMap<>();
        locks.forEach((resource, info) -> snapshot.put(resource, info.clientId));
        return snapshot;
    }

    public void importState(java.util.Map<String, String> activeLocks) {
        locks.clear();
        activeLocks.forEach((resource, client) -> {

            locks.put(resource, new LockInfo(client, LEASE_DURATION_MS));
        });

        System.out.printf("🔄 [%s] Memória de locks reconstruída com sucesso após falha.%n", serverId);
    }

    public boolean tryLock(String resourceId, String clientId) {

        LockInfo currentLock = locks.get(resourceId);

        if (currentLock == null || System.currentTimeMillis() > currentLock.expirationTime) {

            locks.put(resourceId, new LockInfo(clientId, LEASE_DURATION_MS));

            System.out.printf("✅ [%s] LOCK CONCEDIDO para '%s' (Cliente: %s). Lease: 10s%n", serverId, resourceId, clientId);

            Queue<String> queue = waitQueue.get(resourceId);
            if (queue != null) queue.remove(clientId);
            return true;
        }


        if (!currentLock.clientId.equals(clientId)) {
            System.out.printf("⛔ [%s] LOCK NEGADO para '%s'. Em uso por '%s'.%n", serverId, resourceId, currentLock.clientId);
            waitQueue.computeIfAbsent(resourceId, k -> new LinkedList<>()).add(clientId);
            return false;
        }

        return true;
    }

    public boolean unlock(String resourceId, String clientId) {

        LockInfo lock = locks.get(resourceId);

        if (lock != null && lock.clientId.equals(clientId)) {
            locks.remove(resourceId);
            System.out.printf("🔓 [%s] LOCK LIBERADO de '%s' pelo dono (%s)%n", serverId, resourceId, clientId);
            return true;

        } else {

            System.out.printf("❌ [%s] ALERTA: %s tentou liberar lock de terceiros em '%s'%n", serverId, clientId, resourceId);
            return false;
        }
    }

    @Scheduled(fixedRate = 3000)
    public void evictExpiredLocks() {

        long now = System.currentTimeMillis();

        locks.forEach((resource, lockInfo) -> {

            if (now > lockInfo.expirationTime) {
                locks.remove(resource);
                System.out.printf("⏰ [%s] LEASE EXPIRADO! Lock '%s' abandonado por '%s' foi REVOGADO.%n", serverId, resource, lockInfo.clientId);
            }
        });
    }

    public void syncLock(String resourceId, String clientId) {
        locks.put(resourceId, new LockInfo(clientId, LEASE_DURATION_MS));
        System.out.printf("🔄 [%s] BACKUP SINCRONIZADO (LOCK): recurso '%s' -> %s%n", serverId, resourceId, clientId);
    }

    public void syncUnlock(String resourceId) {
        locks.remove(resourceId);
        System.out.printf("🔄 [%s] BACKUP SINCRONIZADO (UNLOCK): recurso '%s' liberado%n", serverId, resourceId);
    }
}