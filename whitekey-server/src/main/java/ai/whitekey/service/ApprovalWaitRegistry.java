package ai.whitekey.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApprovalWaitRegistry {

    private final ConcurrentHashMap<UUID, CompletableFuture<Void>> waiters =
            new ConcurrentHashMap<>();

    public CompletableFuture<Void> register(UUID actionId) {
        var future = new CompletableFuture<Void>();
        waiters.put(actionId, future);
        return future;
    }

    public void unregister(UUID actionId, CompletableFuture<Void> future) {
        waiters.remove(actionId, future);
    }

    public void notifyResolved(UUID actionId) {
        runAfterCommit(
                () -> {
                    var future = waiters.remove(actionId);
                    if (future != null) {
                        future.complete(null);
                    }
                });
    }

    private static void runAfterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            action.run();
                        }
                    });
        } else {
            action.run();
        }
    }
}
