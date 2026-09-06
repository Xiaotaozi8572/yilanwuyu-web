package com.yilan.memory.application.migration;

/** The finite durable cutover modes; only Java commits transitions. */
public enum AuthorityMode {
    LOCAL,
    SHADOW,
    CUTOVER_PREPARED,
    REMOTE
}
