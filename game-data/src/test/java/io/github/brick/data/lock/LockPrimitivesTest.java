package io.github.brick.data.lock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class LockPrimitivesTest {

    @Test
    void lockReqOfAndAccessors() {
        LockReq r = LockReq.of("guild", 7);
        assertEquals("guild", r.entity());
        assertEquals(7L, r.id());
    }

    @Test
    void lockReqEqualsByValue() {
        assertEquals(LockReq.of("player", 1), LockReq.of("player", 1));
    }

    @Test
    void exceptionsAreRuntimeExceptions() {
        assertInstanceOf(RuntimeException.class, new LockAcquireException("拿不到锁"));
        assertInstanceOf(RuntimeException.class, new LockLostException("失锁"));
        assertNotNull(new LockAcquireException("x", new Throwable()).getCause());
    }
}
