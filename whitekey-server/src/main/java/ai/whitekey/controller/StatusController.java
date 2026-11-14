package ai.whitekey.controller;

import ai.whitekey.api.dto.Status;
import ai.whitekey.proxy.AgentProxyServer;
import ai.whitekey.service.ActionService;
import ai.whitekey.service.RuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/status", "/status"})
public class StatusController {

    private final AgentProxyServer proxyServer;
    private final RuleService ruleService;
    private final ActionService actionService;

    public StatusController(
            AgentProxyServer proxyServer,
            RuleService ruleService,
            ActionService actionService) {
        this.proxyServer = proxyServer;
        this.ruleService = ruleService;
        this.actionService = actionService;
    }

    @GetMapping
    public Status getStatus() {
        boolean running = proxyServer.isRunning();
        int port = proxyServer.getBoundPort();
        int rulesCount = ruleService.getRules().size();
        int pendingApprovals = actionService.countPendingApprovals();
        return new Status(running, port, rulesCount, pendingApprovals);
    }
}
