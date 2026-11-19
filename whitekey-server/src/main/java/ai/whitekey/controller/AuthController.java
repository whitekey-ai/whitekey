package ai.whitekey.controller;

import ai.whitekey.service.AdminTokenService;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/auth", "/auth"})
public class AuthController {

    public static final String ERROR_CODE_UNAUTHORIZED = "unauthorized";
    public static final String ERROR_INVALID_ADMIN_TOKEN = "Invalid WhiteKey Admin Token";

    private final AdminTokenService adminTokenService;

    public record VerifyTokenRequest(String token) {}
    public record AuthStatusResponse(boolean authenticated) {}
    public record AuthErrorResponse(String error, String message) {}

    public AuthController(AdminTokenService adminTokenService) {
        this.adminTokenService = adminTokenService;
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody(required = false) VerifyTokenRequest request) {
        String token = request != null ? request.token() : null;

        if (token == null || !adminTokenService.isValidToken(token)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(new AuthErrorResponse(ERROR_CODE_UNAUTHORIZED, ERROR_INVALID_ADMIN_TOKEN));
        }

        return ResponseEntity.ok(new AuthStatusResponse(true));
    }

    @GetMapping("/status")
    public AuthStatusResponse status(HttpServletRequest request) {
        String token = AdminTokenService.extractBearerToken(request.getHeader(HttpHeaders.AUTHORIZATION));
        return new AuthStatusResponse(adminTokenService.isValidToken(token));
    }

    @PostMapping("/logout")
    public AuthStatusResponse logout() {
        return new AuthStatusResponse(false);
    }
}
