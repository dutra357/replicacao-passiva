package com.dutra.rep_server.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class LockService {

    public static class LockInfo {
        public String clientId;
        public long expirationTime;

        public LockInfo(String clientId, long durationMs) {
            this.clientId = clientId;
            this.expirationTime = System.currentTimeMillis() + durationMs;
        }
    }

    private final ConcurrentHashMap<String, LockInfo> locks = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, ConcurrentLinkedQueue<String>> waitQueue = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> clientHeartbeats = new ConcurrentHashMap<>();

    private final String serverId = System.getenv("SERVER_ID");
    private final long LEASE_DURATION_MS = 10000;

    // tempo de vida na fila
    private final long QUEUE_TIMEOUT_MS = 12000;

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
        waitQueue.clear();
        clientHeartbeats.clear();

        activeLocks.forEach((resource, client) -> {
            locks.put(resource, new LockInfo(client, LEASE_DURATION_MS));
        });

        System.out.printf("🔄 [%s] Memória reconstruída após falha: %d locks assumidos.%n", serverId, activeLocks.size());
    }

    public boolean tryLock(String resourceId, String clientId) {

        clientHeartbeats.put(clientId, System.currentTimeMillis());

        LockInfo currentLock = locks.get(resourceId);

        boolean isOwner = currentLock != null &&
                currentLock.clientId.equals(clientId) &&
                System.currentTimeMillis() <= currentLock.expirationTime;

        if (isOwner) {
            Queue<String> queue = waitQueue.get(resourceId);
            if (queue != null) queue.remove(clientId);
            return true;
        }

        ConcurrentLinkedQueue<String> queue = waitQueue.computeIfAbsent(resourceId, k -> new ConcurrentLinkedQueue<>());

        if (!queue.contains(clientId)) {
            queue.add(clientId);
            System.out.printf("⏳ [%s] Cliente %s entrou na posição %d da fila para '%s'.%n",
                    serverId, clientId, queue.size(), resourceId);
        }

        boolean isFree = currentLock == null || System.currentTimeMillis() > currentLock.expirationTime;

        if (isFree) {
            if (clientId.equals(queue.peek())) {
                locks.put(resourceId, new LockInfo(clientId, LEASE_DURATION_MS));
                queue.poll();

                System.out.printf("✅ [%s] LOCK CONCEDIDO para '%s' (Cliente: %s). Lease: 10s%n", serverId, resourceId, clientId);
                return true;
            } else {
                System.out.printf("⛔ [%s] LOCK NEGADO para '%s'. Recurso livre, mas %s não é o primeiro da fila.%n", serverId, resourceId, clientId);
                return false;
            }
        }

        System.out.printf("⛔ [%s] LOCK NEGADO para '%s'. Em uso por '%s'.%n", serverId, resourceId, currentLock.clientId);
        return false;
    }

    public boolean unlock(String resourceId, String clientId) {
        clientHeartbeats.put(clientId, System.currentTimeMillis());

        Queue<String> queue = waitQueue.get(resourceId);
        if (queue != null) queue.remove(clientId);

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

    public boolean renew(String resourceId, String clientId) {
        clientHeartbeats.put(clientId, System.currentTimeMillis());

        LockInfo lock = locks.get(resourceId);

        if (lock != null && lock.clientId.equals(clientId)) {
            lock.expirationTime = System.currentTimeMillis() + LEASE_DURATION_MS;
            System.out.printf("♻️ [%s] LEASE RENOVADO para '%s' pelo cliente %s (+10s)%n", serverId, resourceId, clientId);
            return true;
        }

        System.out.printf("❌ [%s] RENEW NEGADO para '%s'. %s não possui o lock ou já expirou.%n", serverId, resourceId, clientId);
        return false;
    }

    public void syncRenew(String resourceId, String clientId) {
        LockInfo lock = locks.get(resourceId);
        if (lock != null && lock.clientId.equals(clientId)) {
            lock.expirationTime = System.currentTimeMillis() + LEASE_DURATION_MS;
        }
    }

    public String getStatus(String resourceId) {
        LockInfo lock = locks.get(resourceId);
        Queue<String> queue = waitQueue.get(resourceId);
        int queueSize = (queue != null) ? queue.size() : 0;

        if (lock == null || System.currentTimeMillis() > lock.expirationTime) {
            return String.format("LIVRE (Fila de espera: %d)", queueSize);
        } else {
            long tempoRestante = (lock.expirationTime - System.currentTimeMillis()) / 1000;
            return String.format("BLOQUEADO por '%s' (Expira em %ds | Fila: %d)", lock.clientId, tempoRestante, queueSize);
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

        waitQueue.forEach((resource, queue) -> {
            queue.removeIf(clientId -> {
                Long lastSeen = clientHeartbeats.get(clientId);

                boolean isDead = (lastSeen == null || now - lastSeen > QUEUE_TIMEOUT_MS);

                if (isDead) {
                    System.out.printf("🗑️ [%s] EXPULSO DA FILA: '%s' não deu sinal de vida e perdeu a vez em '%s'.%n", serverId, clientId, resource);
                }
                return isDead;
            });
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