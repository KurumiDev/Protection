package io.ruguard.runtime.vm;

import java.io.Serializable;

/**
 * Represents a reference to a field in the custom VM.
 */
public final class VMFieldRef implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String owner;
    public final String name;
    public final String desc;

    public VMFieldRef(String owner, String name, String desc) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
    }

    @Override
    public String toString() {
        return owner + "." + name + ":" + desc;
    }
}
