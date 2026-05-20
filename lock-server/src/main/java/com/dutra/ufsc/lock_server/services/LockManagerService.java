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

    // Tempo de lease padrão: 10 segundos
    private static final long LEASE_TIME_MS = 10000;

    public CompletableFuture<Boolean> acquireLock(String clientId, String resourceId) {

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        synchronized (resourceId.intern()) {
            LockState currentLock = locks.get(resourceId);

            if (currentLock == null) {
                // Recurso livre, concede o lock
                grantLock(clientId, resourceId);
                replicateState(resourceId); // Chama os backups (Replicação)
                future.complete(true);
            } else if (currentLock.getOwnerClientId().equals(clientId)) {
                // Cliente já tem o lock, apenas renova
                renewLock(clientId, resourceId);
                future.complete(true);
            } else {
                // Recurso ocupado, coloca na fila
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
                replicateState(resourceId); // Replica a remoção

                // Acorda o próximo da fila
                Queue<CompletableFuture<Boolean>> queue = waitQueues.get(resourceId);
                if (queue != null && !queue.isEmpty()) {
                    CompletableFuture<Boolean> nextClient = queue.poll();
                    // Aqui você precisaria de uma forma de saber quem era o cliente da fila
                    // Uma melhoria é enfileirar um objeto que contenha o clientId e o Future.
                    nextClient.complete(true);
                }
                return true;
            }
        }
        return false;
    }



}
