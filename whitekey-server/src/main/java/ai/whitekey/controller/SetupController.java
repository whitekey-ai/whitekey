package ai.whitekey.controller;

import ai.whitekey.service.SetupService;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping({"/api/setup", "/setup"})
public class SetupController {
    public record SetupStatusResponse(boolean setupMode) {}

    private final SetupService setup;

    public SetupController(SetupService setup) {
        this.setup = setup;
    }

    @GetMapping
    public SetupStatusResponse status() {
        return new SetupStatusResponse(setup.isSetupMode());
    }

    @PostMapping("/on")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void turnOn() {
        setup.turnOn();
    }

    @PostMapping("/off")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void turnOff() {
        setup.turnOff();
    }
}
