package com.dutra.rep_server.controller;

import com.dutra.rep_server.service.LockService;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import java.util.Arrays;
import java.util.List;

@RestController
@RequestMapping("/api/lock")
public class LockController {

    private final LockService lockService;
    private final List<String> serverList;
    private final String serverId;
    private final RestClient restClient = RestClient.create();

    public LockController(LockService lockService) {
        this.lockService = lockService;
        this.serverId = System.getenv("SERVER_ID");
        this.serverList = Arrays.asList(System.getenv("SERVER_LIST").split(","));
    }

    // --- Endpoints para os Clientes ---

    @PostMapping("/{resourceId}")
    public boolean acquireLock(@PathVariable String resourceId, @RequestParam String clientId) {
        System.out.printf("👑 [%s] (PRIMARY) Requisição de LOCK de %s para '%s'.%n", serverId, clientId, resourceId);

        boolean granted = lockService.tryLock(resourceId, clientId);

        if (granted) {
            replicateLockToBackups(resourceId, clientId);
        }
        return granted;
    }

    @DeleteMapping("/{resourceId}")
    public void releaseLock(@PathVariable String resourceId, @RequestParam String clientId) {
        System.out.printf("👑 [%s] (PRIMARY) Requisição de UNLOCK de %s para '%s'.%n", serverId, clientId, resourceId);

        boolean released = lockService.unlock(resourceId, clientId);

        // Se a liberação foi legítima, replica a exclusão para os backups
        if (released) {
            replicateUnlockToBackups(resourceId);
        }
    }

    // --- Lógica de Replicação Passiva ---

    private void replicateLockToBackups(String resourceId, String clientId) {
        System.out.printf("🔄 [%s] Sincronizando estado (LOCK) com backups...%n", serverId);
        for (String node : serverList) {
            if (!node.contains(System.getenv("SERVER_ID"))) { // Ignora a si mesmo
                try {
                    restClient.post()
                            .uri(node + "/api/lock/" + resourceId + "/sync?clientId=" + clientId)
                            .retrieve()
                            .toBodilessEntity();
                } catch (Exception e) {
                    System.out.printf("⚠️ [%s] Falha ao sincronizar LOCK com backup %s%n", serverId, node);
                }
            }
        }
    }

    private void replicateUnlockToBackups(String resourceId) {
        System.out.printf("🔄 [%s] Sincronizando estado (UNLOCK) com backups...%n", serverId);
        for (String node : serverList) {
            if (!node.contains(System.getenv("SERVER_ID"))) { // Ignora a si mesmo
                try {
                    restClient.delete()
                            .uri(node + "/api/lock/" + resourceId + "/sync")
                            .retrieve()
                            .toBodilessEntity();
                } catch (Exception e) {
                    System.out.printf("⚠️ [%s] Falha ao sincronizar UNLOCK com backup %s%n", serverId, node);
                }
            }
        }
    }

    // --- Endpoints de Sincronização Interna (Chamados pelo Primary) ---

    @PostMapping("/{resourceId}/sync")
    public void syncLock(@PathVariable String resourceId, @RequestParam String clientId) {
        lockService.syncLock(resourceId, clientId);
    }

    @DeleteMapping("/{resourceId}/sync")
    public void syncUnlock(@PathVariable String resourceId) {
        lockService.syncUnlock(resourceId);
    }
}