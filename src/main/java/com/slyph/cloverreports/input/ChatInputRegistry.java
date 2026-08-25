package com.slyph.cloverreports.input;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class ChatInputRegistry {

    private static final ConcurrentMap<UUID, String> OWNERS = new ConcurrentHashMap<>();

    private ChatInputRegistry() {
    }

    public static boolean claim(UUID playerId, String owner) {
        String current = OWNERS.putIfAbsent(playerId, owner);
        return current == null || current.equals(owner);
    }

    public static void release(UUID playerId, String owner) {
        OWNERS.remove(playerId, owner);
    }

    public static boolean isOwnedBy(UUID playerId, String owner) {
        return owner.equals(OWNERS.get(playerId));
    }

    public static void clear(UUID playerId) {
        OWNERS.remove(playerId);
    }
}
