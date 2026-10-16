package io.github.brick.web.config;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("game.web")
public class WebProperties {
    @Min(60) @Max(604800) private long sessionTtlSeconds = 86400;
    @Min(1) @Max(3600) private long authWindowSeconds = 60;
    @Min(1) @Max(10000) private int authIpLimit = 60;
    @Min(1) @Max(1000) private int authAccountLimit = 10;
    @NotBlank private String bizLogDirectory = "logs/biz";
    public long getSessionTtlSeconds() { return sessionTtlSeconds; }
    public void setSessionTtlSeconds(long v) { sessionTtlSeconds = v; }
    public long getAuthWindowSeconds() { return authWindowSeconds; }
    public void setAuthWindowSeconds(long v) { authWindowSeconds = v; }
    public int getAuthIpLimit() { return authIpLimit; }
    public void setAuthIpLimit(int v) { authIpLimit = v; }
    public int getAuthAccountLimit() { return authAccountLimit; }
    public void setAuthAccountLimit(int v) { authAccountLimit = v; }
    public String getBizLogDirectory() { return bizLogDirectory; }
    public void setBizLogDirectory(String v) { bizLogDirectory = v; }
}
