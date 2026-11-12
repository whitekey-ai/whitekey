package ai.whitekey.controller;

import ai.whitekey.api.dto.Status;
import ai.whitekey.proxy.AgentProxyServer;
import ai.whitekey.service.ApprovalRequestService;
import ai.whitekey.service.PageRequests;
import ai.whitekey.service.RuleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/status", "/status"})
public class StatusController {

    private final AgentProxyServer proxyServer;
    private final RuleService ruleService;
    private final ApprovalRequestService approvalService;

    public StatusController(
            AgentProxyServer proxyServer,
            RuleService ruleService,
            ApprovalRequestService approvalService) {
        this.proxyServer = proxyServer;
        this.ruleService = ruleService;
        this.approvalService = approvalService;
    }

    @GetMapping
    public Status getStatus() {
        boolean running = proxyServer.isRunning();
        int port = proxyServer.getBoundPort();
        int rulesCount = ruleService.getRules().size();
        int pendingApprovals = approvalService.getRequests(0, PageRequests.MAX_PAGE_SIZE, true).size();
        return new Status(running, port, rulesCount, pendingApprovals);
    }
}
