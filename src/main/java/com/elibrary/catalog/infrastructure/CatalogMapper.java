package com.elibrary.catalog.infrastructure;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 書目持久層契約。SQL 統一維護於 {@code mybatis/catalog/CatalogMapper.xml}；
 * 本介面只宣告方法與具名參數，不放置行內 SQL。
 */
@Mapper
public interface CatalogMapper {
    // 讀取模型與加鎖查詢。
    BookRow findById(@Param("bookId") long bookId);

    BookRow lockSnapshot(@Param("bookId") long bookId);

    List<BookRow> browse(@Param("query") String query, @Param("categoryId") Long categoryId,
                         @Param("language") String language, @Param("format") String format,
                         @Param("afterId") long afterId, @Param("limit") int limit);

    String lockWriteGate();

    // 書目與投影異動。
    int insertBook(@Param("bookId") long bookId, @Param("isbn") String isbn, @Param("title") String title,
                   @Param("subtitle") String subtitle, @Param("authorsJson") String authorsJson,
                   @Param("publisher") String publisher, @Param("categoryId") long categoryId,
                   @Param("language") String language, @Param("description") String description,
                   @Param("coverUrl") String coverUrl, @Param("publishedDate") LocalDate publishedDate,
                   @Param("format") String format, @Param("now") LocalDateTime now);

    int insertStock(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    int insertBookProjection(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    int updateBook(@Param("bookId") long bookId, @Param("version") long version,
                   @Param("isbn") String isbn, @Param("title") String title,
                   @Param("subtitle") String subtitle, @Param("authorsJson") String authorsJson,
                   @Param("publisher") String publisher, @Param("categoryId") long categoryId,
                   @Param("language") String language, @Param("description") String description,
                   @Param("coverUrl") String coverUrl, @Param("publishedDate") LocalDate publishedDate,
                   @Param("format") String format, @Param("now") LocalDateTime now);

    int changeStatus(@Param("bookId") long bookId, @Param("status") String status,
                     @Param("now") LocalDateTime now);

    int resizeStock(@Param("bookId") long bookId, @Param("newTotal") int newTotal,
                    @Param("now") LocalDateTime now);

    Long lockBookProjection(@Param("bookId") long bookId);

    int beginBookProjectionRebuild(@Param("bookId") long bookId, @Param("now") LocalDateTime now);

    Long bookProjectionGeneration(@Param("bookId") long bookId);
}
