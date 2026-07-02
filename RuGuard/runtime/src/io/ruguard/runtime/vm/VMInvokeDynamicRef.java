package io.ruguard.runtime.vm;

import java.io.Serializable;

/**
 * Represents a reference to an invokedynamic call in the custom VM.
 */
public final class VMInvokeDynamicRef implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String name;
    public final String desc;
    public final VMMethodRef bsm;
    public final Object[] bsmArgs;

    public VMInvokeDynamicRef(String name, String desc, VMMethodRef bsm, Object[] bsmArgs) {
        this.name = name;
        this.desc = desc;
        this.bsm = bsm;
        this.bsmArgs = bsmArgs;
    }
}
