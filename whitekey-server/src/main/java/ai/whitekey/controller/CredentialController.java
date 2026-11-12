package ai.whitekey.controller;

import ai.whitekey.api.dto.CreateCredentialRequest;
import ai.whitekey.api.dto.Credential;
import ai.whitekey.api.dto.CredentialPreset;
import ai.whitekey.service.CredentialPresetService;
import ai.whitekey.service.CredentialService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/credentials")
public class CredentialController {

    private final CredentialService credentialService;
    private final CredentialPresetService presetService;

    public CredentialController(
            CredentialService credentialService,
            CredentialPresetService presetService) {
        this.credentialService = credentialService;
        this.presetService = presetService;
    }

    @GetMapping
    public List<Credential> list() {
        return credentialService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Credential create(@Valid @RequestBody CreateCredentialRequest request) {
        return credentialService.create(request);
    }

    @GetMapping("/presets")
    public List<CredentialPreset> listPresets() {
        return presetService.getAllPresets();
    }

    @GetMapping("/{name}")
    public Credential get(@PathVariable String name) {
        return credentialService.getByName(name);
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        credentialService.deleteByName(name);
    }
}
