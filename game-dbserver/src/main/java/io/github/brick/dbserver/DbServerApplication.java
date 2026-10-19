package io.github.brick.dbserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 落盘进程入口。无 web（无 Tomcat），Spring Boot 默认 NONE web 类型。
 * 落盘编排（FlushOrchestrator/FlushScheduler/GracefulShutdown）由 DbServerConfiguration 装配。
 */
@SpringBootApplication
public class DbServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbServerApplication.class, args);
    }
}
