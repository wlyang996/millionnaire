package com.millionnaire.gateway.characters;

/** Binary image storage, separate from catalog metadata. */
public interface CharacterObjectStore {
    boolean ready();
    default void verify() {}
    void put(String key, byte[] png);
    byte[] get(String key);
    default void delete(String key){throw new UnsupportedOperationException("对象存储不支持删除");}
}
