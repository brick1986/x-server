package io.github.brick.dbserver.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 默认值守卫。范围越界（@Min/@Max）的启动失败行为由 Spring 绑定校验保证，
 * 此处只钉默认值——它们是「零配置本地启动」的兜底，被改动应当是显式决定。
 */
class DbServerPropertiesTest {

    @Test
    void defaultsMatchSpec() {
        DbServerProperties p = new DbServerProperties();
        assertThat(p.getFlushIntervalMillis()).isEqualTo(2000L);   // 架构 §4.3 的 1~3s
        assertThat(p.getChunkSize()).isEqualTo(500);               // 落盘 spec §3
        assertThat(p.getLockLeaseSeconds()).isEqualTo(60L);        // 落盘 spec §5
        assertThat(p.getShutdownTimeoutSeconds()).isEqualTo(20L);  // 落盘 spec §6
    }
}
