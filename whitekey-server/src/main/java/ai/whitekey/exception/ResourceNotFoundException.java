package ai.whitekey.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException forRule(Object id) {
        return new ResourceNotFoundException("Rule not found: " + id);
    }

    public static ResourceNotFoundException forCredential(String name) {
        return new ResourceNotFoundException("Credential not found: " + name);
    }

    public static ResourceNotFoundException forAction(Object id) {
        return new ResourceNotFoundException("Action not found: " + id);
    }

    public static ResourceNotFoundException forApprovalRequest(Object id) {
        return new ResourceNotFoundException("Approval request not found: " + id);
    }
}
