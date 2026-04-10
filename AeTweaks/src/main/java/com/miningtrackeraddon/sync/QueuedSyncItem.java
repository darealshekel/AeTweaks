package com.miningtrackeraddon.sync;

import com.google.gson.JsonObject;
import java.util.UUID;

final class QueuedSyncItem
{
    String id;
    SyncItemType type;
    String dedupeKey;
    JsonObject payload;
    long createdAtMs;
    int retryCount;
    long lastRetryAtMs;
    long nextRetryAtMs;

    QueuedSyncItem()
    {
    }

    static QueuedSyncItem create(SyncItemType type, String dedupeKey, JsonObject payload, long now)
    {
        QueuedSyncItem item = new QueuedSyncItem();
        item.id = UUID.randomUUID().toString();
        item.type = type;
        item.dedupeKey = dedupeKey == null ? "" : dedupeKey;
        item.payload = payload == null ? new JsonObject() : payload.deepCopy();
        item.createdAtMs = now;
        item.retryCount = 0;
        item.lastRetryAtMs = 0L;
        item.nextRetryAtMs = 0L;
        return item;
    }

    boolean isValid()
    {
        return this.id != null
        && this.id.isBlank() == false
        && this.type != null
        && this.payload != null
        && this.createdAtMs > 0L
        && this.dedupeKey != null;
    }

    QueuedSyncItem copy()
    {
        QueuedSyncItem item = new QueuedSyncItem();
        item.id = this.id;
        item.type = this.type;
        item.dedupeKey = this.dedupeKey;
        item.payload = this.payload == null ? null : this.payload.deepCopy();
        item.createdAtMs = this.createdAtMs;
        item.retryCount = this.retryCount;
        item.lastRetryAtMs = this.lastRetryAtMs;
        item.nextRetryAtMs = this.nextRetryAtMs;
        return item;
    }

    String summary()
    {
        return this.type + (this.dedupeKey == null || this.dedupeKey.isBlank() ? "" : " [" + this.dedupeKey + "]");
    }
}
