package com.elibrary.catalog.infrastructure;

import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookStatus;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 书目与可借数量的持久化查询模型。 */
@Getter
@Setter
@NoArgsConstructor
public class BookRow {
    // 书目识别与可搜寻中继资料。
    private long bookId;
    private String isbn;
    private String title;
    private String subtitle;
    private String authors;
    private String publisher;
    private long categoryId;
    private String language;
    private String description;
    private String coverUrl;
    private LocalDate publishedDate;
    private BookFormat format;
    private BookStatus status;
    private long version;

    // 乐观锁与稽核时间。
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    // 由书目查询联结 lending_stock 取得；未联结库存时可为 null。
    private Integer totalCopies;
    private Integer availableCopies;
    private Long stockVersion;
}
