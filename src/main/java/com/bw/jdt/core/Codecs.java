package com.bw.jdt.core;

import com.bw.jdt.core.format.CabCodec;
import com.bw.jdt.core.format.ContainerCodec;
import com.bw.jdt.core.format.SevenZCodec;
import com.bw.jdt.core.format.ZipCodec;

import java.util.EnumMap;
import java.util.Map;

/** Registry of the container codecs, shared by decomposition and reassembly. */
public final class Codecs {

    private static final Map<ContainerFormat, ContainerCodec> CODECS = new EnumMap<>(ContainerFormat.class);

    static {
        CODECS.put(ContainerFormat.ZIP, new ZipCodec());
        CODECS.put(ContainerFormat.CAB, new CabCodec());
        CODECS.put(ContainerFormat.SEVEN_Z, new SevenZCodec());
    }

    private Codecs() {
    }

    public static ContainerCodec of(ContainerFormat format) {
        ContainerCodec codec = CODECS.get(format);
        if (codec == null) {
            throw new IllegalStateException("no codec for " + format);
        }
        return codec;
    }
}
