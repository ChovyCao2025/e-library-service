package com.elibrary.catalog.application;

/** 搜索投影暂时无法查询时使用的内部例外，由应用服务转换为稳定业务错误。 */
public class BookSearchUnavailableException extends RuntimeException {
    public BookSearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
