package com.ideaqr.gateway.dto;

import lombok.Builder;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * A lean, framework-agnostic pagination envelope returned by the listing endpoints
 * (audit 3.1). Carries the already-mapped rows plus the page metadata the SPA needs
 * to render a pager, without serialising Spring's full {@code Page} structure.
 */
@Data
@Builder
public class PageResponse<T> {

    private List<T> content;
    private int page;
    private int size;
    private long totalElements;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;

    /** Wrap a Spring {@link Page} (used for its metadata) with the mapped content rows. */
    public static <T> PageResponse<T> of(Page<?> page, List<T> content) {
        return PageResponse.<T>builder()
                .content(content)
                .page(page.getNumber())
                .size(page.getSize())
                .totalElements(page.getTotalElements())
                .totalPages(page.getTotalPages())
                .hasNext(page.hasNext())
                .hasPrevious(page.hasPrevious())
                .build();
    }
}
