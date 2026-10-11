package com.millionnaire.gateway.characters;

/** Binary image storage, separate from catalog metadata. */
public interface CharacterObjectStore {
    boolean ready();
    default void verify() {}
    void put(String key, byte[] png);
    byte[] get(String key);
}
