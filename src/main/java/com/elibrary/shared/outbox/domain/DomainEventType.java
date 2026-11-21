package com.elibrary.shared.outbox.domain;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 对外发布的稳定领域事件名称。 */
@Getter
@RequiredArgsConstructor
public enum DomainEventType {
    BOOK_CREATED("BookCreated"),
    BOOK_UPDATED("BookUpdated"),
    BOOK_PUBLISHED("BookPublished"),
    BOOK_OFFLINED("BookOfflined"),
    BOOK_BORROWED("BookBorrowed"),
    BOOK_RETURNED("BookReturned"),
    STOCK_RESIZED("StockResized");

    private final String wireValue;
}
