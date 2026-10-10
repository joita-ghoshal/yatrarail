package com.yatrarail.dto.common;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * A page of results in the shape the frontend actually needs.
 * <p>
 * Spring's own {@code Page} serialises a great deal of internals (page sort internals, unpaged
 * markers, callback flags) that a client never uses, and its JSON shape is not something we want to
 * depend on across a version bump. This is a small, explicit contract: the rows, the 0-based page,
 * the size, the total count and whether there is a next page.
 *
 * @param <T> the row type
 */
@Getter
@AllArgsConstructor
public class PageResponse<T> {

    private final List<T> content;
    private final int page;
    private final int size;
    private final long totalElements;
    private final int totalPages;
    private final boolean first;
    private final boolean last;

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }

    /** Maps a page of entities into a page of DTOs without losing the pagination metadata. */
    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isFirst(),
                page.isLast());
    }
}
