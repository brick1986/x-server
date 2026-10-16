package io.github.brick.web.log;
import java.util.Map;
import java.util.Set;

/** Field-level intent, never a committed-state assertion or credential snapshot. */
public record BizEvent(String operationId,String requestId,long timestamp,long accountId,long uid,
                       String operation,Map<String,String> before,Map<String,String> after) {
    public BizEvent {
        before=Map.copyOf(before); after=Map.copyOf(after);
        if (!Set.of("CREATE_PLAYER","RENAME").contains(operation)
            || !Set.of("displayName").containsAll(before.keySet()) || !Set.of("displayName").containsAll(after.keySet()))
            throw new IllegalArgumentException("Unsupported business event fields");
    }
}
