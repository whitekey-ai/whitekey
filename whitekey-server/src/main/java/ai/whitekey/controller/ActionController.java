package ai.whitekey.controller;

import ai.whitekey.api.dto.Action;
import ai.whitekey.entity.ActionStatus;
import ai.whitekey.mapper.ActionDtoMapper;
import ai.whitekey.service.ActionService;
import ai.whitekey.service.PageRequests;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/actions")
public class ActionController {
    private final ActionService service;
    private final ActionDtoMapper mapper;

    public ActionController(ActionService service, ActionDtoMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<Action> list(
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_STRING) int page,
            @RequestParam(defaultValue = PageRequests.DEFAULT_PAGE_SIZE_STRING) int limit,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) ActionStatus status,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String host) {
        return service.getActions(page, limit, type, status, method, host)
                .stream()
                .map(mapper::toActionDto)
                .toList();
    }

    @GetMapping("/{id}")
    public Action get(@PathVariable UUID id) {
        return mapper.toActionDto(service.getAction(id));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear() {
        service.clearFinished();
    }
}
