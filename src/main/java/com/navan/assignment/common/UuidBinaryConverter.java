package com.navan.assignment.common;

import java.nio.ByteBuffer;
import java.util.UUID;
import org.jooq.Converter;

public final class UuidBinaryConverter implements Converter<byte[], UUID> {

    @Override
    public UUID from(byte[] bytes) {
        if (bytes == null) return null;
        if (bytes.length != 16) {
            throw new IllegalArgumentException("Expected 16-byte UUID blob, got " + bytes.length);
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes);
        return new UUID(buf.getLong(), buf.getLong());
    }

    @Override
    public byte[] to(UUID uuid) {
        if (uuid == null) return null;
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
        return buf.array();
    }

    @Override public Class<byte[]> fromType() { return byte[].class; }
    @Override public Class<UUID> toType() { return UUID.class; }
}
