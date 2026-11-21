package com.elibrary.catalog.interfaces;

import com.elibrary.catalog.application.CatalogService;
import com.elibrary.shared.web.api.ApiPagination;
import com.elibrary.shared.web.api.Resp;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供公开书目查询 API。一般使用者只能浏览已上架书籍，
 * 动态可借数量会与书目资料一起回传。
 */
@Validated
@RestController
@RequestMapping("/api/v1/books")
@RequiredArgsConstructor
public class BookController {
    private final CatalogService service;

    /**
     * 依书名、分类、语言及格式筛选已上架书目，结果按照书籍标识码递增排列。
     *
     * @param q           书名开头查询；未提供时不限制书名，最长 100 个字符
     * @param categoryId  分类标识码；未提供时不限制分类
     * @param language    书籍语言代码；未提供时不限制语言，最长 10 个字符
     * @param format      书籍格式，可使用 {@code EBOOK} 或 {@code JOURNAL}；未提供时不限制格式
     * @param afterBookId 上一页最后一笔书籍标识码；未提供时从第一页开始
     * @param size        每页笔数，必须位于系统允许的分页范围内
     * @return 包含本页书目、下一页标识码及是否仍有数据的统一响应
     */
    @GetMapping
    public Resp<BookDtos.BookPage> browse(
            @RequestParam(required = false) @Size(max = 100) String q,
            @RequestParam(required = false) @Positive Long categoryId,
            @RequestParam(required = false) @Size(max = 10) String language,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) @Positive Long afterBookId,
            @RequestParam(defaultValue = ApiPagination.DEFAULT_PAGE_SIZE)
            @Min(ApiPagination.MIN_PAGE_SIZE) @Max(ApiPagination.MAX_PAGE_SIZE) int size) {
        return Resp.success(service.browse(q, categoryId, language, format, afterBookId, size));
    }

    /**
     * 查询单本已上架书籍。响应包含动态库存，因此停用 HTTP 缓存以避免显示过期余量。
     *
     * @param bookId   书籍标识码，必须为正整数
     * @param response HTTP 响应，用于设置禁止缓存响应头
     * @return 包含书目详情与当前可借数量的统一响应
     */
    @GetMapping("/{bookId}")
    public Resp<BookDtos.BookResponse> get(@PathVariable @Positive long bookId, HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
        return Resp.success(service.get(bookId, false));
    }
}
