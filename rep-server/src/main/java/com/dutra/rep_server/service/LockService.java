package com.dutra.rep_server.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.HashMap;
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

    // Tempo de vida na fila
    private final long QUEUE_TIMEOUT_MS = 12000;

    private volatile boolean crashed = false;

    // REQUISITO CFT: Versão lógica do estado global (Logical Clock / Epoch)
    private volatile long stateVersion = 0;

    public boolean isCrashed() { return crashed; }
    public void setCrashed(boolean crashed) { this.crashed = crashed; }
    public long getStateVersion() { return stateVersion; }

    // Exporta o estado atual acoplado com a versão lógica autoritativa
    public Map<String, Object> exportState() {
        Map<String, Object> stateSnapshot = new HashMap<>();
        Map<String, String> activeLocks = new HashMap<>();

        locks.forEach((resource, info) -> activeLocks.put(resource, info.clientId));

        stateSnapshot.put("version", stateVersion);
        stateSnapshot.put("locks", activeLocks);
        return stateSnapshot;
    }

    // Importa o estado baseado na maior versão da rede
    @SuppressWarnings("unchecked")
    public void importState(Map<String, Object> clusterState) {
        locks.clear();
        waitQueue.clear();
        clientHeartbeats.clear();

        long incomingVersion = ((Number) clusterState.get("version")).longValue();
        Map<String, String> incomingLocks = (Map<String, String>) clusterState.get("locks");

        if (incomingLocks != null) {
            incomingLocks.forEach((resource, client) -> {
                locks.put(resource, new LockInfo(client, LEASE_DURATION_MS));
            });
        }

        this.stateVersion = incomingVersion;
        System.out.printf("🔄 [%s] Memória reconstruída após falha: %d locks assumidos na Versão v%d.%n", serverId, locks.size(), stateVersion);
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
                stateVersion++; // Modificação autoritativa: avança a versão
                locks.put(resourceId, new LockInfo(clientId, LEASE_DURATION_MS));
                queue.poll();

                System.out.printf("✅ [%s] LOCK CONCEDIDO [v%d] para '%s' (Cliente: %s). Lease: 10s%n", serverId, stateVersion, resourceId, clientId);
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
            stateVersion++; // Modificação autoritativa
            locks.remove(resourceId);
            System.out.printf("🔓 [%s] LOCK LIBERADO [v%d] de '%s' pelo dono (%s)%n", serverId, stateVersion, resourceId, clientId);
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
            stateVersion++; // Modificação autoritativa
            lock.expirationTime = System.currentTimeMillis() + LEASE_DURATION_MS;
            System.out.printf("♻️ [%s] LEASE RENOVADO [v%d] para '%s' pelo cliente %s (+10s)%n", serverId, stateVersion, resourceId, clientId);
            return true;
        }

        System.out.printf("❌ [%s] RENEW NEGADO para '%s'. %s não possui o lock ou já expirou.%n", serverId, resourceId, clientId);
        return false;
    }

    // --- Métodos de Consulta ---

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

    // --- Rotinas de Limpeza (Background) ---

    @Scheduled(fixedRate = 3000)
    public void evictExpiredLocks() {
        // Se o servidor estiver em modo de recuperação, não expulsa ninguém para evitar divergências
        if (crashed) return;

        long now = System.currentTimeMillis();

        locks.forEach((resource, lockInfo) -> {
            if (now > lockInfo.expirationTime) {
                stateVersion++; // A expiração de lease altera o estado lógico do cluster
                locks.remove(resource);
                System.out.printf("⏰ [%s] LEASE EXPIRADO! Lock '%s' [v%d] abandonado por '%s' foi REVOGADO.%n", serverId, resource, stateVersion, lockInfo.clientId);
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

    // --- Sincronização Passiva (Chamada pelos outros servidores) ---

    public void syncLock(String resourceId, String clientId, long version) {
        this.stateVersion = version;
        locks.put(resourceId, new LockInfo(clientId, LEASE_DURATION_MS));
        System.out.printf("🔄 [%s] BACKUP SINCRONIZADO (LOCK v%d): recurso '%s' -> %s%n", serverId, version, resourceId, clientId);
    }

    public void syncUnlock(String resourceId, long version) {
        this.stateVersion = version;
        locks.remove(resourceId);
        System.out.printf("🔄 [%s] BACKUP SINCRONIZADO (UNLOCK v%d): recurso '%s' liberado%n", serverId, version, resourceId);
    }

    public void syncRenew(String resourceId, String clientId, long version) {
        this.stateVersion = version;
        LockInfo lock = locks.get(resourceId);
        if (lock != null && lock.clientId.equals(clientId)) {
            lock.expirationTime = System.currentTimeMillis() + LEASE_DURATION_MS;
            System.out.printf("🔄 [%s] BACKUP SINCRONIZADO (RENEW v%d): recurso '%s' (+10s)%n", serverId, version, resourceId);
        }
    }
}