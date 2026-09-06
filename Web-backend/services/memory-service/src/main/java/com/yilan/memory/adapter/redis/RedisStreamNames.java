package com.yilan.memory.adapter.redis;

import java.util.Objects;

/**
 * Versioned internal Redis Streams. The prefix is deliberately fixed: a
 * publisher must not silently place memory events into an unversioned stream.
 */
public final class RedisStreamNames {

    public static final String PREFIX = "memory:v1:";

    private RedisStreamNames() {
    }

    public static String events() {
        return PREFIX + "events";
    }

    public static String projection() {
        return PREFIX + "projection";
    }

    public static String deadLetter() {
        return PREFIX + "dead-letter";
    }

    public static void requireSupportedPrefix(String streamPrefix) {
        if (!PREFIX.equals(Objects.requireNonNull(streamPrefix, "streamPrefix"))) {
            throw new IllegalArgumentException("streamPrefix must be " + PREFIX);
        }
    }
}
