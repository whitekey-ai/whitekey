package ai.whitekey.service;

import org.springframework.data.domain.PageRequest;

public final class PageRequests {
    public static final int MAX_PAGE = 100_000;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final String DEFAULT_PAGE_SIZE_STRING = "50";
    public static final String DEFAULT_PAGE_STRING = "0";

    private PageRequests() {}

    public static PageRequest of(int page, int limit) {
        if (page < 0 || page > MAX_PAGE || limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page must be between 0 and " + MAX_PAGE + ", and limit between 1 and " + MAX_PAGE_SIZE);
        }
        return PageRequest.of(page, limit);
    }
}
