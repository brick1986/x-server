package io.github.brick.web.api;

import io.github.brick.contract.game.ErrorResponse;
import io.github.brick.data.lock.*;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.*;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;

public final class ApiErrorWriter {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorWriter.class);

    public void write(HttpServletResponse response, int status, String code, String message, String requestId) throws IOException {
        response.setStatus(status);
        response.setContentType("application/x-protobuf");
        response.setHeader("Cache-Control", "no-store");
        response.getOutputStream().write(ErrorResponse.newBuilder().setCode(code).setMessage(message).setRequestId(requestId == null ? "" : requestId).build().toByteArray());
    }

    public void failure(HttpServletResponse response, Exception error) throws IOException {
        if (response.isCommitted()) return;
        int status = 500;
        String code = "INTERNAL_ERROR", message = "服务暂不可用";
        if (error instanceof GameException e) {
            status = e.status();
            code = e.code();
            message = e.getMessage();
        } else if (error instanceof HttpMessageNotReadableException) {
            status = 400;
            code = "INVALID_MESSAGE";
            message = "请求消息不合法";
        } else if (error instanceof HttpMediaTypeNotSupportedException) {
            status = 415;
            code = "UNSUPPORTED_MEDIA_TYPE";
            message = "请使用 Protobuf";
        } else if (error instanceof HttpRequestMethodNotSupportedException) {
            status = 405;
            code = "METHOD_NOT_ALLOWED";
            message = "请使用 POST";
        } else if (error instanceof HttpMediaTypeNotAcceptableException) {
            status = 406;
            code = "NOT_ACCEPTABLE";
        } else if (error instanceof NoHandlerFoundException || error instanceof NoResourceFoundException) {
            status = 404;
            code = "NOT_FOUND";
        } else if (error instanceof LockAcquireException || error instanceof LockLostException || error instanceof com.mongodb.MongoException || error instanceof org.redisson.client.RedisException) {
            status = 503;
            code = "DEPENDENCY_UNAVAILABLE";
        }
        if (status >= 500)
            LOG.error("Request failed requestId={} type={}", MDC.get("requestId"), error.getClass().getName());
        response.resetBuffer();
        write(response, status, code, message, MDC.get("requestId"));
    }
}
