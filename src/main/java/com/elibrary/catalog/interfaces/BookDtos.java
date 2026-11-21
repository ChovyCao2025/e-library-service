package com.elibrary.catalog.interfaces;

import com.elibrary.catalog.domain.BookFormat;
import com.elibrary.catalog.domain.BookStatus;
import com.elibrary.catalog.infrastructure.BookRow;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 书目 API 的请求与回应模型。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class BookDtos {
    /** 借阅容量快照的权威来源。 */
    public enum AvailabilitySource {
        DATABASE
    }

    /** 书籍目前的可借数量快照。 */
    @Getter
    @RequiredArgsConstructor
    public static final class Availability {
        /** 查询当下仍可建立借阅的席位数量。 */
        private final Integer available;

        /** 管理端为此书设定的借阅总容量，包含已借出与可借席位。 */
        private final Integer total;

        /** 应用程式组装此库存快照的 UTC 时间。 */
        private final Instant asOf;

        /** 库存快照来源；目前 MySQL 权威查询固定回传 {@code DATABASE}。 */
        private final AvailabilitySource source;
    }

    /** 书目明细与借阅供应状态。 */
    @Getter
    @RequiredArgsConstructor
    public static final class BookResponse {
        /** 书籍雪花识别码的十进位字串，避免 JavaScript 整数精度遗失。 */
        private final String bookId;

        /** ISBN；书目未提供时为 {@code null}。 */
        private final String isbn;

        /** 对外显示的书名。 */
        private final String title;

        /** 副标题；未提供时为 {@code null}。 */
        private final String subtitle;

        /** 依输入顺序保存的作者名称清单。 */
        private final List<String> authors;

        /** 出版者名称；未提供时为 {@code null}。 */
        private final String publisher;

        /** 书目分类识别码的十进位字串。 */
        private final String categoryId;

        /** 书目使用的语言代码或语言标记，例如 {@code en}、{@code zh-TW}。 */
        private final String language;

        /** 书目内容简介；未提供时为 {@code null}。 */
        private final String description;

        /** 封面资源位置；未提供时为 {@code null}。 */
        private final String coverUrl;

        /** 出版日期，不包含时间与时区；未提供时为 {@code null}。 */
        private final LocalDate publishedDate;

        /** 数位内容格式，目前为 {@code EBOOK} 或 {@code JOURNAL}。 */
        private final BookFormat format;

        /** 书目生命周期状态：{@code DRAFT}、{@code PUBLISHED} 或 {@code OFFLINE}。 */
        private final BookStatus status;

        /** 书目乐观锁版本；书目中继资料或状态改变时递增。 */
        private final long version;

        /** 动态借阅容量快照；书目尚未建立库存资料时为 {@code null}。 */
        private final Availability availability;

        /** 将持久化查询模型转为 API 回应，并解析作者 JSON 与库存快照。 */
        public static BookResponse from(BookRow row, ObjectMapper objectMapper, Instant asOf) {
            try {
                // authors 在 MySQL 以 JSON 阵列保存，API 对外还原为具型别的字串清单。
                List<String> authors = objectMapper.readValue(row.getAuthors(), new TypeReference<>() { });

                // LEFT JOIN 未取得库存列时保持 null；正常书目则标示权威资料库来源与快照时间。
                Availability availability = row.getAvailableCopies() == null ? null
                        : new Availability(row.getAvailableCopies(), row.getTotalCopies(), asOf,
                        AvailabilitySource.DATABASE);

                // BIGINT UNSIGNED 识别码统一转为十进位字串，避免 JSON 客户端发生精度损失。
                return new BookResponse(Long.toUnsignedString(row.getBookId()), row.getIsbn(), row.getTitle(),
                        row.getSubtitle(), authors, row.getPublisher(), Long.toUnsignedString(row.getCategoryId()),
                        row.getLanguage(), row.getDescription(), row.getCoverUrl(), row.getPublishedDate(),
                        row.getFormat(), row.getStatus(), row.getVersion(), availability);
            } catch (JacksonException exception) {
                // 作者 JSON 由服务自身写入；解析失败代表持久化资料已违反内部格式约定。
                throw new IllegalStateException("Invalid authors JSON in catalog", exception);
            }
        }
    }

    /** 依书籍识别码寻址的分页查询结果。 */
    @Getter
    @RequiredArgsConstructor
    public static final class BookPage {
        /** 本页依书籍识别码升幂排列的书目资料。 */
        private final List<BookResponse> items;

        /** 下一页应传入的 afterBookId；已到最后一页时为 {@code null}。 */
        private final String nextBookId;

        /** 是否仍有下一页资料。 */
        private final boolean hasMore;
    }

    /** 创建与更新书目共用的元数据输入。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public abstract static class BookMetadataRequest {
        /** 可选 ISBN，最多 20 字元；非空值在资料库中必须唯一。 */
        @Size(max = 20)
        private String isbn;

        /** 必填书名，最多 200 字元。 */
        @NotBlank
        @Size(max = 200)
        private String title;

        /** 可选副标题，最多 200 字元。 */
        @Size(max = 200)
        private String subtitle;

        /** 至少一位作者；每个名称必须非空且最多 100 字元。 */
        @NotEmpty
        private List<@NotBlank @Size(max = 100) String> authors;

        /** 可选出版者名称，最多 100 字元。 */
        @Size(max = 100)
        private String publisher;

        /** 必填分类识别码，由 JSON 数值或十进位字串转换为正整数。 */
        @NotNull
        @Positive
        private Long categoryId;

        /** 必填语言代码或语言标记，最多 10 字元。 */
        @NotBlank
        @Size(max = 10)
        private String language;

        /** 可选书目简介，最多 10,000 字元。 */
        @Size(max = 10000)
        private String description;

        /** 可选封面资源位置，最多 500 字元。 */
        @Size(max = 500)
        private String coverUrl;

        /** 可选出版日期，JSON 格式为 {@code yyyy-MM-dd}。 */
        private LocalDate publishedDate;

        /** 必填内容格式，不区分大小写接受 {@code EBOOK} 或 {@code JOURNAL}。 */
        @NotBlank
        private String format;
    }

    /** 创建书目的输入数据；新书由后端初始化版本。 */
    @NoArgsConstructor
    public static final class BookCreateRequest extends BookMetadataRequest {
    }

    /** 更新书目的输入数据；version 为页面读取到的当前书目版本。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static final class BookUpdateRequest extends BookMetadataRequest {
        /** 页面读取到的书目版本，用于防止旧表单覆盖其他管理员已经提交的数据。 */
        @NotNull
        @Positive
        private Long version;
    }

    /** 调整书籍总馆藏数量的输入资料。 */
    @Getter
    @Setter
    @NoArgsConstructor
    public static class StockRequest {
        /** 新的借阅总容量，范围为 0 至 100,000，且不得低于目前已借出数量。 */
        @Min(0)
        @Max(100000)
        private int totalCopies;
    }
}
