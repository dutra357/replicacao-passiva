package com.dutra.ufsc.lock_server.model.records;

import java.util.concurrent.CompletableFuture;

public record WaitNode(String clientId, CompletableFuture<Boolean> future) {

}
