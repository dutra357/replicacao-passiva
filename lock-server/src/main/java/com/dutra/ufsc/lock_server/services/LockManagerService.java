package com.dutra.ufsc.lock_server.services;

import com.dutra.ufsc.lock_server.model.LockState;
import com.dutra.ufsc.lock_server.model.records.WaitNode;
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

    // estado atual dos locks
    private final Map<String, LockState> locks = new ConcurrentHashMap<>();

    // Fila de espera
    private final Map<String, Queue<WaitNode>> waitQueues = new ConcurrentHashMap<>();

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
                // Recurso ocupado: cria o nó composto e coloca na fila FIFO
                log.info("Recurso {} ocupado. Cliente {} entrou na fila de espera.", resourceId, clientId);
                WaitNode node = new WaitNode(clientId, future);
                waitQueues.computeIfAbsent(resourceId, k -> new ConcurrentLinkedQueue<>()).add(node);
            }
        }
        return future;
    }

    public boolean unlock(String clientId, String resourceId) {
        synchronized (resourceId.intern()) {

            LockState currentLock = locks.get(resourceId);

            if (currentLock != null && currentLock.getOwnerClientId().equals(clientId)) {
                locks.remove(resourceId);
                log.info("Lock do recurso {} liberado pelo cliente {}.", resourceId, clientId);

                replicateState(resourceId);

                passBaton(resourceId);

                return true;
            }
        }
        return false;
    }

    private void passBaton(String resourceId) {
        Queue<WaitNode> queue = waitQueues.get(resourceId);

        if (queue != null) {
            WaitNode nextNode = queue.poll();

            if (nextNode != null) {
                grantLock(nextNode.clientId(), resourceId);

                replicateState(resourceId);

                nextNode.future().complete(true);

                log.info("Bastão repassado: Lock do recurso {} concedido ao cliente {} da fila.", resourceId, nextNode.clientId());
            }
        }
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

    // Controle de Leases
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

        // expirar se não for renovado
        newLock.setExpirationTimeMillis(System.currentTimeMillis() + LEASE_TIME_MS);

        // Registra o lock no servidor
        locks.put(resourceId, newLock);

        log.info("Lock concedido: Cliente {} adquiriu o recurso {}", clientId, resourceId);
    }
}