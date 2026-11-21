package com.elibrary.shared.web.api;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 公开 API 共用的分页范围与预设值。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ApiPagination {
    public static final String DEFAULT_PAGE_SIZE = "20";
    public static final int MIN_PAGE_SIZE = 1;
    public static final int MAX_PAGE_SIZE = 50;
}
