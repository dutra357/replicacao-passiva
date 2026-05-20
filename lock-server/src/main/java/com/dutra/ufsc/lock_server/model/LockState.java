package com.dutra.ufsc.lock_server.model;

public class LockState {
    private String resourceId;
    private String ownerClientId;
    private long expirationTimeMillis;

    public LockState() {}

    public LockState(String resourceId, String ownerClientId, long expirationTimeMillis) {
        this.resourceId = resourceId;
        this.ownerClientId = ownerClientId;
        this.expirationTimeMillis = expirationTimeMillis;
    }

    public String getResourceId() {
        return resourceId;
    }

    public void setResourceId(String resourceId) {
        this.resourceId = resourceId;
    }

    public String getOwnerClientId() {
        return ownerClientId;
    }

    public void setOwnerClientId(String ownerClientId) {
        this.ownerClientId = ownerClientId;
    }

    public long getExpirationTimeMillis() {
        return expirationTimeMillis;
    }

    public void setExpirationTimeMillis(long expirationTimeMillis) {
        this.expirationTimeMillis = expirationTimeMillis;
    }

    @Override
    public String toString() {
        return "LockState{" +
                "resourceId='" + resourceId + '\'' +
                ", ownerClientId='" + ownerClientId + '\'' +
                ", expirationTimeMillis=" + expirationTimeMillis +
                '}';
    }
}
