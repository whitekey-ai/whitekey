package ai.whitekey.controller;

import ai.whitekey.api.dto.ApprovalRequest;
import ai.whitekey.api.dto.ResolveApprovalRequest;
import ai.whitekey.entity.ApprovalRequestEntity;
import ai.whitekey.entity.ApprovalRequestStatus;
import ai.whitekey.mapper.ActionDtoMapper;
import ai.whitekey.service.ApprovalRequestService;
import ai.whitekey.service.PageRequests;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/approval-requests", "/approval-requests"})
public class ApprovalRequestController {
    private final ApprovalRequestService service;
    private final ActionDtoMapper mapper;

    public ApprovalRequestController(ApprovalRequestService service, ActionDtoMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<ApprovalRequest> listRequests(
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_SIZE_STRING) int limit,
            @RequestParam(defaultValue = "false") boolean pending) {
        return service.getRequests(page, limit, pending)
                .stream()
                .map(mapper::toApprovalRequestDto)
                .toList();
    }

    @GetMapping("/{id}")
    public ApprovalRequest getRequest(@PathVariable UUID id) {
        return mapper.toApprovalRequestDto(service.getRequest(id));
    }

    @PostMapping("/{id}/resolve")
    public ApprovalRequest resolveRequest(
            @PathVariable UUID id, @Valid @RequestBody ResolveApprovalRequest request) {
        ApprovalRequestStatus status = ApprovalRequestStatus.fromDecision(request.decision());
        ApprovalRequestEntity response = service.resolveRequest(id, status, request.saveRule());
        return mapper.toApprovalRequestDto(response);
    }
}
