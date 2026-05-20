package com.dutra.ufsc.lock_server.services;

import com.dutra.ufsc.lock_server.model.LockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

@Service
public class LockManagerService {

    private final Logger log = LoggerFactory.getLogger(LockManagerService.class);

    // Armazena o estado atual dos locks
    private final Map<String, LockState> locks = new ConcurrentHashMap<>();

    // Fila de espera para cada recurso
    private final Map<String, Queue<CompletableFuture<Boolean>>> waitQueues = new ConcurrentHashMap<>();

    private static final long LEASE_TIME_MS = 10000;

    public CompletableFuture<Boolean> acquireLock(String clientId, String resourceId) {

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        synchronized (resourceId.intern()) {
            LockState currentLock = locks.get(resourceId);

            if (currentLock == null) {
                grantLock(clientId, resourceId);
                replicateState(resourceId);
                future.complete(true);
            } else if (currentLock.getOwnerClientId().equals(clientId)) {
                renewLock(clientId, resourceId);
                future.complete(true);

            } else {
                log.info("Recurso {} ocupado. Cliente {} na fila.", resourceId, clientId);
                waitQueues.computeIfAbsent(resourceId, k -> new ConcurrentLinkedQueue<>()).add(future);
            }
        }
        return future;
    }

    public boolean unlock(String clientId, String resourceId) {
        synchronized (resourceId.intern()) {
            LockState currentLock = locks.get(resourceId);
            if (currentLock != null && currentLock.getOwnerClientId().equals(clientId)) {
                locks.remove(resourceId);
                log.info("Lock liberado pelo cliente {} para o recurso {}", clientId, resourceId);
                replicateState(resourceId);

                Queue<CompletableFuture<Boolean>> queue = waitQueues.get(resourceId);

                if (queue != null && !queue.isEmpty()) {
                    CompletableFuture<Boolean> nextClient = queue.poll();
                    nextClient.complete(true);
                }
                return true;
            }
        }
        return false;
    }

    public boolean renewLock(String clientId, String resourceId) {
        synchronized (resourceId.intern()) {
            LockState currentLock = locks.get(resourceId);
            if (currentLock != null && currentLock.getOwnerClientId().equals(clientId)) {
                currentLock.setExpirationTimeMillis(System.currentTimeMillis() + LEASE_TIME_MS);
                replicateState(resourceId);
                log.info("Lease renovado para cliente {} no recurso {}", clientId, resourceId);
                return true;
            }
        }
        return false;
    }

    // Controle de Leases ---
    @Scheduled(fixedRate = 1000)
    public void evictExpiredLocks() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, LockState> entry : locks.entrySet()) {
            if (now > entry.getValue().getExpirationTimeMillis()) {
                log.warn("Lease expirado para o recurso {}. Removendo lock.", entry.getKey());

                // Simula que o próprio sistema chamou o unlock
                unlock(entry.getValue().getOwnerClientId(), entry.getKey());
            }
        }
    }

    private void replicateState(String resourceId) {
        // Implementar chamada RestTemplate/WebClient para os URLs de BACKUPS
        // enviando o estado atual de 'locks.get(resourceId)'
    }

    private void grantLock(String clientId, String resourceId) {
        LockState newLock = new LockState();
        newLock.setResourceId(resourceId);
        newLock.setOwnerClientId(clientId);

        // Momento exato em que o lock vai expirar se não for renovado
        newLock.setExpirationTimeMillis(System.currentTimeMillis() + LEASE_TIME_MS);

        // Registra o lock na memória do servidor
        locks.put(resourceId, newLock);

        log.info("Lock concedido: Cliente {} adquiriu o recurso {}", clientId, resourceId);
    }


}
