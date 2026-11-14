package ai.whitekey.controller;

import ai.whitekey.exception.ApprovalConflictException;
import ai.whitekey.exception.ConflictException;
import ai.whitekey.exception.ResourceNotFoundException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    public static final String DEFAULT_NOT_FOUND_MESSAGE = "Resource not found";
    public static final String DEFAULT_INVALID_INPUT_MESSAGE = "Invalid input";
    public static final String DEFAULT_VALIDATION_FAILED_MESSAGE = "Validation failed";

    public record ErrorResponse(String message) {}

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(ResourceNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage() != null ? ex.getMessage() : DEFAULT_NOT_FOUND_MESSAGE));
    }

    @ExceptionHandler({ApprovalConflictException.class, ConflictException.class})
    public ResponseEntity<ErrorResponse> conflict(RuntimeException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse(exception.getMessage()));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> dataIntegrityConflict(DataIntegrityViolationException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("Resource conflict"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidInput(IllegalArgumentException exception) {
        return ResponseEntity.badRequest()
                .body(new ErrorResponse(exception.getMessage() != null ? exception.getMessage() : DEFAULT_INVALID_INPUT_MESSAGE));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> validationError(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(DEFAULT_VALIDATION_FAILED_MESSAGE);
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }
}
