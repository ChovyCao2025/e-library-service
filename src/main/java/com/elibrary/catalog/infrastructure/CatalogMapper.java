package com.elibrary.catalog.infrastructure;

import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookMetadata;
import com.elibrary.catalog.domain.BookStatus;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 书目持久层契约。SQL 统一维护于 {@code mybatis/catalog/CatalogMapper.xml}；
 * 本介面只宣告方法与具名参数，不放置行内 SQL。
 */
@Mapper
public interface CatalogMapper {
    // 读取模型与加锁查询。
    BookRow findById(@Param("bookId") long bookId);

    BookRow lockSnapshot(@Param("bookId") long bookId);

    List<BookRow> browse(@Param("query") String query, @Param("categoryId") Long categoryId,
                         @Param("language") String language, @Param("format") BookFormat format,
                         @Param("status") BookStatus status,
                         @Param("afterId") long afterId, @Param("limit") int limit);

    List<BookRow> scanForSearch(@Param("afterId") long afterId, @Param("limit") int limit);

    // 书目异动。
    int insertBook(@Param("bookId") long bookId, @Param("metadata") BookMetadata metadata,
                   @Param("status") BookStatus status, @Param("now") LocalDateTime now);

    int updateBook(@Param("bookId") long bookId, @Param("version") long version,
                   @Param("metadata") BookMetadata metadata, @Param("now") LocalDateTime now);

    int changeStatus(@Param("bookId") long bookId,
                     @Param("allowedSources") List<BookStatus> allowedSources,
                     @Param("status") BookStatus status, @Param("now") LocalDateTime now);

}
