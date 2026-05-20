package com.dutra.ufsc.lock_server.controller;

import com.dutra.ufsc.lock_server.services.LockManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/locks")
public class LockController {

    @Autowired
    private LockManagerService lockService;

    @PostMapping("/{resourceId}/lock")
    public CompletableFuture<ResponseEntity<String>> lock(@PathVariable String resourceId, @RequestParam String clientId) {
        return lockService.acquireLock(clientId, resourceId)
                .thenApply(granted -> ResponseEntity.ok("Lock concedido"));
    }

    @PostMapping("/{resourceId}/unlock")
    public ResponseEntity<String> unlock(@PathVariable String resourceId, @RequestParam String clientId) {
        boolean success = lockService.unlock(clientId, resourceId);
        return success ? ResponseEntity.ok("Unlocked") : ResponseEntity.status(403).body("Não é o dono do lock");
    }

    // Endpoints para /renew, /status e /replicate (para os backups receberem o estado)
}
