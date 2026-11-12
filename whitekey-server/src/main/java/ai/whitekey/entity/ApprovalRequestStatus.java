package ai.whitekey.entity;

public enum ApprovalRequestStatus {
    PENDING("Awaiting human approval"),
    APPROVED("Human approval granted"),
    REJECTED("Human approval rejected"),
    CLIENT_DISCONNECTED("Client closed connection before approval"),
    APPROVAL_TIMEOUT("Approval request timed out"),
    SETUP_SESSION_ENDED("Setup session ended before approval");

    private final String message;

    ApprovalRequestStatus(String message) {
        this.message = message;
    }

    public String message() {
        return message;
    }

    public boolean isExpired() {
        return this == CLIENT_DISCONNECTED || this == APPROVAL_TIMEOUT || this == SETUP_SESSION_ENDED;
    }

    public static ApprovalRequestStatus fromDecision(String decision) {
        if (decision == null || decision.isBlank()) {
            throw new IllegalArgumentException("Decision must not be blank");
        }
        String normalized = decision.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (normalized) {
            case "ALLOW", "APPROVED" -> APPROVED;
            case "BLOCK", "DENIED", "REJECTED" -> REJECTED;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid approval decision: " + decision);
                }
            }
        };
    }
}
