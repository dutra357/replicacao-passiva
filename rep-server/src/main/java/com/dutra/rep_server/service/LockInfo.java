package com.dutra.rep_server.service;

public class LockInfo {

    String clientId;
    long expirationTime;

    LockInfo(String clientId, long durationMs) {
        this.clientId = clientId;
        this.expirationTime = System.currentTimeMillis() + durationMs;
    }
}
