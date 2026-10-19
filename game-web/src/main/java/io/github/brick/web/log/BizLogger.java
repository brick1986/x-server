package io.github.brick.web.log;

@FunctionalInterface
public interface BizLogger {
    void append(BizEvent event);
}
