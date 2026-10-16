package io.github.brick.web.log;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.brick.web.api.GameException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.regex.Pattern;

public final class FileBizLogger implements BizLogger,AutoCloseable {
    private static final Pattern OWNED=Pattern.compile("biz-(\\d{1,16})-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.jsonl");
    private final Path directory;
    private final Clock clock;
    private final ObjectMapper mapper=new ObjectMapper();
    private FileChannel channel;
    private LocalDate day;
    private boolean closed;
    public FileBizLogger(Path directory,Clock clock) { this.directory=directory; this.clock=clock; }
    @Override public synchronized void append(BizEvent event) {
        if (closed) throw unavailable();
        try {
            LocalDate now=LocalDate.ofInstant(clock.instant(),ZoneOffset.UTC);
            if (channel==null || !now.equals(day)) {
                if(channel!=null) channel.close();
                Files.createDirectories(directory);
                cleanup();
                channel=FileChannel.open(directory.resolve("biz-"+clock.millis()+"-"+UUID.randomUUID()+".jsonl"),StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE);
                day=now;
            }
            var buffer=ByteBuffer.wrap((mapper.writeValueAsString(event)+"\n").getBytes(StandardCharsets.UTF_8));
            while(buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        } catch(IOException e) {
            // Never append after a potentially partial record: require a fresh process/logger.
            closed=true;
            if(channel!=null) try { channel.close(); } catch(IOException ignored) {}
            throw unavailable();
        }
    }
    private void cleanup() throws IOException {
        long cutoff=clock.millis()-Duration.ofHours(48).toMillis();
        try(var files=Files.newDirectoryStream(directory)) {
            for(Path file:files) {
                var matcher=OWNED.matcher(file.getFileName().toString());
                if(matcher.matches() && Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)
                    && Long.parseLong(matcher.group(1))<cutoff) Files.delete(file);
            }
        }
    }
    @Override public synchronized void close() {
        closed=true;
        if(channel!=null) try { channel.close(); } catch(IOException e) { throw unavailable(); }
    }
    private static GameException unavailable() { return new GameException(503,"BUSINESS_LOG_UNAVAILABLE","业务日志暂不可用"); }
}
