package ai.whitekey.service;

import ai.whitekey.entity.ApprovalRequestStatus;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ApprovalWaitRegistry {

    private final ConcurrentHashMap<UUID, CompletableFuture<ApprovalRequestStatus>> waiters =
            new ConcurrentHashMap<>();

    public CompletableFuture<ApprovalRequestStatus> register(UUID approvalId) {
        var future = new CompletableFuture<ApprovalRequestStatus>();
        waiters.put(approvalId, future);
        return future;
    }

    public void unregister(UUID approvalId, CompletableFuture<ApprovalRequestStatus> future) {
        waiters.remove(approvalId, future);
    }

    public void notifyResolved(UUID approvalId, ApprovalRequestStatus status) {
        runAfterCommit(
                () -> {
                    var future = waiters.remove(approvalId);
                    if (future != null) {
                        future.complete(status);
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
