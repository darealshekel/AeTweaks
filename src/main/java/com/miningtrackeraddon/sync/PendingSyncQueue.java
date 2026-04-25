package com.miningtrackeraddon.sync;

import com.google.gson.JsonObject;
import com.miningtrackeraddon.MiningTrackerAddon;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PendingSyncQueue
{
    private static final String LOG_PREFIX = "[MMM_SYNC]";

    public interface Sender
    {
        SyncSendResult send(QueuedSyncItem item);
    }

    public interface Listener
    {
        default void onLoaded(Snapshot snapshot) {}
        default void onLoadFailed(String detail) {}
        default void onItemQueued(QueuedSyncItem item, boolean replaced, Snapshot snapshot) {}
        default void onFlushStarted(String reason, Snapshot snapshot) {}
        default void onFlushFinished(String reason, Snapshot snapshot) {}
        default void onItemSucceeded(QueuedSyncItem item, SyncSendResult result, Snapshot snapshot) {}
        default void onRetryScheduled(QueuedSyncItem item, SyncSendResult result, long nextRetryAtMs, Snapshot snapshot) {}
        default void onItemDropped(QueuedSyncItem item, SyncSendResult result, Snapshot snapshot) {}
        default void onPersistenceFailed(String detail, Snapshot snapshot) {}
    }

    public record Snapshot(int queueSize, long lastSuccessfulSyncAtMs, boolean flushActive, Map<SyncItemType, Integer> countsByType)
    {
        public int countFor(SyncItemType type)
        {
            return this.countsByType.getOrDefault(type, 0);
        }
    }

    private final Object lock = new Object();
    private final PendingSyncStore store;
    private final Sender sender;
    private final Listener listener;
    private final ExecutorService flushExecutor;
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);

    private final List<QueuedSyncItem> items = new ArrayList<>();
    private long lastSuccessfulSyncAtMs;
    private volatile boolean flushActive;

    public PendingSyncQueue(Path storePath, Sender sender, Listener listener)
    {
        this(storePath, sender, listener, runnable -> {
            Thread thread = new Thread(runnable, "MMM-SyncQueue");
            thread.setDaemon(true);
            return thread;
        });
    }

    PendingSyncQueue(Path storePath, Sender sender, Listener listener, ThreadFactory threadFactory)
    {
        this.store = new PendingSyncStore(storePath);
        this.sender = sender;
        this.listener = listener;
        this.flushExecutor = Executors.newSingleThreadExecutor(threadFactory);
    }

    public void initialize()
    {
        try
        {
            PendingSyncStore.StoredState state = this.store.load();
            synchronized (this.lock)
            {
                this.items.clear();
                this.items.addAll(state.items);
                this.lastSuccessfulSyncAtMs = state.lastSuccessfulSyncAtMs;
            }
            this.listener.onLoaded(snapshot());
        }
        catch (Exception exception)
        {
            this.listener.onLoadFailed(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
        }
    }

    public void shutdown()
    {
        this.flushExecutor.shutdownNow();
    }

    public void enqueue(SyncItemType type, String dedupeKey, JsonObject payload, boolean replaceExisting)
    {
        QueuedSyncItem changedItem;
        boolean replaced = false;

        synchronized (this.lock)
        {
            long now = System.currentTimeMillis();
            QueuedSyncItem existing = replaceExisting ? findByTypeAndKey(type, dedupeKey) : null;
            if (existing != null)
            {
                existing.payload = payload == null ? new JsonObject() : payload.deepCopy();
                changedItem = existing.copy();
                replaced = true;
            }
            else
            {
                QueuedSyncItem item = QueuedSyncItem.create(type, dedupeKey, payload, now);
                this.items.add(item);
                this.items.sort(Comparator.comparingLong(value -> value.createdAtMs));
                changedItem = item.copy();
            }

            persistLocked();
        }

        this.listener.onItemQueued(changedItem, replaced, snapshot());
    }

    public void requestFlush(String reason)
    {
        if (this.flushScheduled.compareAndSet(false, true) == false)
        {
            MiningTrackerAddon.LOGGER.info("{} send-skipped-flush-guard reason={} detail=flush_already_scheduled_or_active",
                    LOG_PREFIX,
                    reason == null || reason.isBlank() ? "manual" : reason);
            return;
        }

        this.flushExecutor.execute(() -> flushLoop(reason == null || reason.isBlank() ? "manual" : reason));
    }

    public void forceFlush(String reason)
    {
        synchronized (this.lock)
        {
            for (QueuedSyncItem item : this.items)
            {
                item.nextRetryAtMs = 0L;
            }
            persistLocked();
        }

        requestFlush(reason);
    }

    public Snapshot snapshot()
    {
        synchronized (this.lock)
        {
            return snapshotLocked();
        }
    }

    List<QueuedSyncItem> snapshotItemsForTests()
    {
        synchronized (this.lock)
        {
            List<QueuedSyncItem> copy = new ArrayList<>();
            for (QueuedSyncItem item : this.items)
            {
                copy.add(item.copy());
            }
            return copy;
        }
    }

    private void flushLoop(String reason)
    {
        this.flushActive = true;
        this.listener.onFlushStarted(reason, snapshot());

        try
        {
            while (true)
            {
                QueuedSyncItem item = nextDueItem();
                if (item == null)
                {
                    logNoDueItem(reason);
                    return;
                }

                SyncSendResult result = this.sender.send(item.copy());
                handleResult(item, result);
            }
        }
        finally
        {
            this.flushActive = false;
            this.flushScheduled.set(false);
            this.listener.onFlushFinished(reason, snapshot());
        }
    }

    private void handleResult(QueuedSyncItem attemptedItem, SyncSendResult result)
    {
        long now = System.currentTimeMillis();

        synchronized (this.lock)
        {
            QueuedSyncItem current = findById(attemptedItem.id);
            if (current == null)
            {
                MiningTrackerAddon.LOGGER.warn("{} send-skipped-item-invalid id={} reason=item_missing_during_handle",
                        LOG_PREFIX,
                        attemptedItem.id);
                return;
            }

            if (result.outcome() == SyncSendResult.Outcome.SUCCESS)
            {
                this.items.removeIf(item -> item.id.equals(attemptedItem.id));
                this.lastSuccessfulSyncAtMs = now;
                persistLocked();
                this.listener.onItemSucceeded(attemptedItem, result, snapshotLocked());
                return;
            }

            if (result.outcome() == SyncSendResult.Outcome.DROP)
            {
                this.items.removeIf(item -> item.id.equals(attemptedItem.id));
                persistLocked();
                this.listener.onItemDropped(attemptedItem, result, snapshotLocked());
                return;
            }

            current.retryCount++;
            current.lastRetryAtMs = now;
            current.nextRetryAtMs = now + SyncRetryPolicy.computeDelayMs(current.retryCount);
            persistLocked();
            this.listener.onRetryScheduled(current.copy(), result, current.nextRetryAtMs, snapshotLocked());
        }
    }

    private QueuedSyncItem nextDueItem()
    {
        synchronized (this.lock)
        {
            long now = System.currentTimeMillis();
            return this.items.stream()
                    .filter(item -> item.nextRetryAtMs <= now)
                    .min(Comparator.comparingLong(item -> item.createdAtMs))
                    .map(QueuedSyncItem::copy)
                    .orElse(null);
        }
    }

    private QueuedSyncItem findByTypeAndKey(SyncItemType type, String dedupeKey)
    {
        String normalizedKey = dedupeKey == null ? "" : dedupeKey;
        for (QueuedSyncItem item : this.items)
        {
            if (item.type == type && normalizedKey.equals(item.dedupeKey))
            {
                return item;
            }
        }

        return null;
    }

    private QueuedSyncItem findById(String id)
    {
        for (QueuedSyncItem item : this.items)
        {
            if (item.id.equals(id))
            {
                return item;
            }
        }

        return null;
    }

    private Snapshot snapshotLocked()
    {
        EnumMap<SyncItemType, Integer> counts = new EnumMap<>(SyncItemType.class);
        for (QueuedSyncItem item : this.items)
        {
            counts.merge(item.type, 1, Integer::sum);
        }

        return new Snapshot(this.items.size(), this.lastSuccessfulSyncAtMs, this.flushActive, Map.copyOf(counts));
    }

    private void persistLocked()
    {
        try
        {
            this.store.save(this.items, this.lastSuccessfulSyncAtMs);
        }
        catch (Exception exception)
        {
            this.listener.onPersistenceFailed(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage(), snapshotLocked());
        }
    }

    static String formatInstant(long timestampMs)
    {
        if (timestampMs <= 0L)
        {
            return "never";
        }

        return Instant.ofEpochMilli(timestampMs).toString();
    }

    private void logNoDueItem(String reason)
    {
        synchronized (this.lock)
        {
            if (this.items.isEmpty())
            {
                MiningTrackerAddon.LOGGER.info("{} send-skipped-queue-empty reason={}", LOG_PREFIX, reason);
                return;
            }

            long now = System.currentTimeMillis();
            long nextRetryAt = this.items.stream()
                    .mapToLong(item -> item.nextRetryAtMs)
                    .min()
                    .orElse(0L);
            long waitMs = Math.max(0L, nextRetryAt - now);
            MiningTrackerAddon.LOGGER.info(
                    "{} send-skipped-item-not-due reason={} queueSize={} nextRetryAt={} waitMs={}",
                    LOG_PREFIX,
                    reason,
                    this.items.size(),
                    formatInstant(nextRetryAt),
                    waitMs
            );
        }
    }
}
