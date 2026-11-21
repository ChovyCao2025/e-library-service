package com.elibrary.catalog.domain;

import java.util.Locale;

/**
 * 平台目前支援的数位内容格式。
 * EBOOK 代表电子书，JOURNAL 代表数位期刊；值会直接持久化至书目资料表。
 */
public enum BookFormat {
    EBOOK,
    JOURNAL;

    /** 将必填 API 输入转为支援的内容格式。 */
    public static BookFormat from(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Book format is required");
        }
        return valueOf(value.strip().toUpperCase(Locale.ROOT));
    }

    /** 将可选查询条件转为内容格式。 */
    public static BookFormat fromNullable(String value) {
        return value == null || value.isBlank() ? null : from(value);
    }
}
