package com.dutra.rep_server.controller;

import com.dutra.rep_server.service.LockService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

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

    // ENDPOINTS DE CLIENTES
    @PostMapping("/{resourceId}")
    public boolean acquireLock(@PathVariable String resourceId, @RequestParam String clientId) {

        if (lockService.isCrashed()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulação de falha isolada.");
        }

        System.out.printf("👑 [%s] (PRIMARY) Requisição de LOCK de %s para '%s'.%n", serverId, clientId, resourceId);
        boolean granted = lockService.tryLock(resourceId, clientId);
        if (granted) replicateLockToBackups(resourceId, clientId);
        return granted;
    }

    @DeleteMapping("/{resourceId}")
    public void releaseLock(@PathVariable String resourceId, @RequestParam String clientId) {

        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        System.out.printf("👑 [%s] (PRIMARY) Requisição de UNLOCK de %s para '%s'.%n", serverId, clientId, resourceId);

        boolean released = lockService.unlock(resourceId, clientId);

        // Replica para backup apenas se liberado com sucesso (legitimo)
        if (released) {
            replicateUnlockToBackups(resourceId);
        }
    }

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

    @PutMapping("/{resourceId}/renew")
    public boolean renewLock(@PathVariable String resourceId, @RequestParam String clientId) {

        if (lockService.isCrashed()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulação de falha isolada.");
        }
        System.out.printf("👑 [%s] (PRIMARY) Requisição de RENEW de %s para '%s'.%n", serverId, clientId, resourceId);

        boolean renewed = lockService.renew(resourceId, clientId);

        if (renewed) {
            replicateRenewToBackups(resourceId, clientId);
        }
        return renewed;
    }

    private void replicateRenewToBackups(String resourceId, String clientId) {

        System.out.printf("🔄 [%s] Sincronizando estado (RENEW) com backups...%n", serverId);

        for (String node : serverList) {
            if (!node.contains(System.getenv("SERVER_ID"))) {

                try {
                    restClient.put()
                            .uri(node + "/api/lock/" + resourceId + "/renew/sync?clientId=" + clientId)
                            .retrieve()
                            .toBodilessEntity();

                } catch (Exception e) {
                    System.out.printf("⚠️ [%s] Falha ao sincronizar RENEW com backup %s%n", serverId, node);
                }
            }
        }
    }

    @GetMapping("/{resourceId}/status")
    public String getLockStatus(@PathVariable String resourceId) {

        if (lockService.isCrashed()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Simulação de falha isolada.");
        }

        return lockService.getStatus(resourceId);
    }

    // ENDPOINTS PARA SERVIDORES
    @PostMapping("/{resourceId}/sync")
    public void syncLock(@PathVariable String resourceId, @RequestParam String clientId) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        lockService.syncLock(resourceId, clientId);
    }

    @DeleteMapping("/{resourceId}/sync")
    public void syncUnlock(@PathVariable String resourceId) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        lockService.syncUnlock(resourceId);
    }

    @GetMapping("/state")
    public Map<String, String> getClusterState() {
        if (lockService.isCrashed()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
        return lockService.exportState();
    }

    @PutMapping("/{resourceId}/renew/sync")
    public void syncRenewLock(@PathVariable String resourceId, @RequestParam String clientId) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        lockService.syncRenew(resourceId, clientId);
    }
}