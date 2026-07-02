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

    @PostMapping("/{resourceId}")
    public boolean acquireLock(@PathVariable String resourceId, @RequestParam String clientId) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Offline.");

        boolean granted = lockService.tryLock(resourceId, clientId);
        if (granted) replicateToBackups("LOCK", resourceId, clientId);
        return granted;
    }

    @DeleteMapping("/{resourceId}")
    public void releaseLock(@PathVariable String resourceId, @RequestParam String clientId) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        boolean released = lockService.unlock(resourceId, clientId);
        if (released) replicateToBackups("UNLOCK", resourceId, null);
    }

    @PutMapping("/{resourceId}/renew")
    public boolean renewLock(@PathVariable String resourceId, @RequestParam String clientId) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);

        boolean renewed = lockService.renew(resourceId, clientId);
        if (renewed) replicateToBackups("RENEW", resourceId, clientId);
        return renewed;
    }

    @GetMapping("/{resourceId}/status")
    public String getLockStatus(@PathVariable String resourceId) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return lockService.getStatus(resourceId);
    }

    @GetMapping("/state")
    public Map<String, Object> getClusterState() {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        return lockService.exportState();
    }

    // --- Endpoints de Sincronização Interna baseados em Versão ---

    @PostMapping("/{resourceId}/sync")
    public void syncLock(@PathVariable String resourceId, @RequestParam String clientId, @RequestParam long version) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        lockService.syncLock(resourceId, clientId, version);
    }

    @DeleteMapping("/{resourceId}/sync")
    public void syncUnlock(@PathVariable String resourceId, @RequestParam long version) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        lockService.syncUnlock(resourceId, version);
    }

    @PutMapping("/{resourceId}/renew/sync")
    public void syncRenewLock(@PathVariable String resourceId, @RequestParam String clientId, @RequestParam long version) {
        if (lockService.isCrashed()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        lockService.syncRenew(resourceId, clientId, version);
    }

    private void replicateToBackups(String action, String resourceId, String clientId) {
        long currentVersion = lockService.getStateVersion();
        for (String node : serverList) {
            if (!node.contains(serverId)) {
                try {
                    String url = node + "/api/lock/" + resourceId + "/sync?version=" + currentVersion;
                    if ("LOCK".equals(action)) {
                        restClient.post().uri(url + "&clientId=" + clientId).retrieve().toBodilessEntity();
                    } else if ("UNLOCK".equals(action)) {
                        restClient.delete().uri(url).retrieve().toBodilessEntity();
                    } else if ("RENEW".equals(action)) {
                        restClient.put().uri(node + "/api/lock/" + resourceId + "/renew/sync?version=" + currentVersion + "&clientId=" + clientId).retrieve().toBodilessEntity();
                    }
                } catch (Exception e) {
                    System.out.printf("⚠️ [%s] Falha de replicação síncrona com backup %s%n", serverId, node);
                }
            }
        }
    }
}