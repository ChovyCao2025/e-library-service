package com.elibrary.catalog.domain;

import java.util.List;

/**
 * 书目上架生命周期。
 * 只有 PUBLISHED 可供一般使用者浏览与借阅，DRAFT 与 OFFLINE 仅供管理端维护。
 */
public enum BookStatus {
    DRAFT,
    PUBLISHED,
    OFFLINE;

    /** 一般使用者只能浏览及借阅已上架书目。 */
    public boolean isPubliclyVisible() {
        return this == PUBLISHED;
    }

    /** 借阅流程只接受已上架书目。 */
    public boolean isBorrowable() {
        return this == PUBLISHED;
    }

    /**
     * 返回迁移到当前目标状态时允许的来源状态。
     * 上架允许草稿或已下架书目进入，只有已上架书目可以执行下架。
     */
    public List<BookStatus> allowedSourceStatuses() {
        return switch (this) {
            case PUBLISHED -> List.of(DRAFT, OFFLINE);
            case OFFLINE -> List.of(PUBLISHED);
            case DRAFT -> List.of();
        };
    }
}
