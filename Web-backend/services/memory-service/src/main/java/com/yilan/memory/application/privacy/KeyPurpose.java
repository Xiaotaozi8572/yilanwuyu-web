package com.yilan.memory.application.privacy;

/**
 * Purpose separation for the M3 source-material bridge and M4 protected
 * authority storage. The provider may use the same learner DEK reference,
 * but authenticated binding always includes this purpose.
 */
public enum KeyPurpose {
    INTERACTION_SOURCE,
    MEMORY_CANDIDATE,
    MEMORY_VERSION_VALUE
}
