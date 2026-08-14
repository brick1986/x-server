package io.github.brick.data.lock;

/** 加锁请求：(entity, id)。 */
public record LockReq(String entity, long id) {
    public static LockReq of(String entity, long id) {
        return new LockReq(entity, id);
    }
}
