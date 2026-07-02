package io.ruguard.runtime.vm;

import java.io.Serializable;

/**
 * Represents a reference to a method in the custom VM.
 */
public final class VMMethodRef implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String owner;
    public final String name;
    public final String desc;
    public final int tag; // Used for dynamic method handles or invokedynamic tags if necessary

    public VMMethodRef(String owner, String name, String desc, int tag) {
        this.owner = owner;
        this.name = name;
        this.desc = desc;
        this.tag = tag;
    }

    @Override
    public String toString() {
        return owner + "." + name + desc;
    }
}
