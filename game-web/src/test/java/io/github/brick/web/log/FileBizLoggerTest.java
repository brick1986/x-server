package io.github.brick.web.log;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.brick.web.api.GameException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

class FileBizLoggerTest {
    @TempDir Path temp;
    private BizEvent event(String id) { return new BizEvent(id,"request",100,1,11,"RENAME",Map.of("displayName","旧名字"),Map.of("displayName","新\"名字\\")); }
    @Test void concurrentAppendsAreCompleteJsonLines() throws Exception {
        try (var logger = new FileBizLogger(temp,Clock.systemUTC()); var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks=new ArrayList<Future<?>>();
            for(int i=0;i<20;i++) { String id="op"+i; tasks.add(pool.submit(() -> logger.append(event(id)))); }
            for(var task:tasks) task.get(5,TimeUnit.SECONDS);
        }
        try(var files=Files.list(temp)) {
            var lines=Files.readAllLines(files.findFirst().orElseThrow());
            assertThat(lines).hasSize(20);
            var ids=new HashSet<String>();
            for(String line:lines) { var json=new ObjectMapper().readTree(line); ids.add(json.get("operationId").asText()); assertThat(json.get("after").get("displayName").asText()).isEqualTo("新\"名字\\"); }
            assertThat(ids).hasSize(20);
        }
    }
    @Test void failuresPropagateAndOnlyOwnedExpiredFilesAreDeleted() throws Exception {
        Path invalid=Files.writeString(temp.resolve("file"),"keep");
        try(var logger=new FileBizLogger(invalid,Clock.systemUTC())) {
            assertThatThrownBy(() -> logger.append(event("1"))).isInstanceOfSatisfying(GameException.class,e -> assertThat(e.status()).isEqualTo(503));
        }
        long now=Instant.parse("2026-10-16T12:00:00Z").toEpochMilli();
        Path old=Files.writeString(temp.resolve("biz-"+(now-Duration.ofDays(3).toMillis())+"-00000000-0000-0000-0000-000000000001.jsonl"),"old");
        Path recent=Files.writeString(temp.resolve("biz-"+(now-1000)+"-00000000-0000-0000-0000-000000000002.jsonl"),"recent");
        try(var logger=new FileBizLogger(temp,Clock.fixed(Instant.ofEpochMilli(now),ZoneOffset.UTC))) { logger.append(event("2")); }
        assertThat(old).doesNotExist(); assertThat(recent).exists(); assertThat(invalid).hasContent("keep");
    }
    @Test void rotationKeepsRecentRecordsAndRejectsSensitiveFields() throws Exception {
        class MutableClock extends Clock {
            Instant time=Instant.parse("2026-10-16T12:00:00Z");
            public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;} public Instant instant(){return time;}
        }
        var clock=new MutableClock();
        try(var logger=new FileBizLogger(temp,clock)) {
            logger.append(event("1")); clock.time=clock.time.plus(Duration.ofDays(1)); logger.append(event("2"));
            assertThatThrownBy(() -> logger.append(new BizEvent("3","r",100,1,11,"RENAME",Map.of(),Map.of("password","secret")))).isInstanceOf(IllegalArgumentException.class);
        }
        try(var files=Files.list(temp)) { assertThat(files.count()).isEqualTo(2); }
    }
    @Test void retentionKeepsLateRecordsForAtLeastFortyEightHours() throws Exception {
        class MutableClock extends Clock {
            Instant time=Instant.parse("2026-10-16T00:01:00Z");
            public ZoneId getZone(){return ZoneOffset.UTC;}
            public Clock withZone(ZoneId zone){return this;}
            public Instant instant(){return time;}
        }
        var clock=new MutableClock();
        try(var logger=new FileBizLogger(temp,clock)) {
            logger.append(event("early"));
            Path first;
            try(var files=Files.list(temp)){first=files.findFirst().orElseThrow();}
            clock.time=Instant.parse("2026-10-16T23:59:00Z");
            logger.append(event("late"));
            clock.time=Instant.parse("2026-10-18T00:02:00Z");
            logger.append(event("next-day"));
            assertThat(first).exists();
            assertThat(Files.readString(first)).contains("late");
            clock.time=Instant.parse("2026-10-19T00:01:00Z");
            logger.append(event("expired"));
            assertThat(first).doesNotExist();
        }
    }
}
