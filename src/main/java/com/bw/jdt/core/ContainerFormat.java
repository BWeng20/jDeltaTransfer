package com.bw.jdt.core;

/** Archive container formats the decomposer can look inside of. */
public enum ContainerFormat {
    ZIP(0),
    CAB(1),
    SEVEN_Z(2);

    private final int id;

    ContainerFormat(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static ContainerFormat byId(int id) {
        for (ContainerFormat f : values()) {
            if (f.id == id) {
                return f;
            }
        }
        throw new IllegalArgumentException("unknown container format id " + id);
    }
}
