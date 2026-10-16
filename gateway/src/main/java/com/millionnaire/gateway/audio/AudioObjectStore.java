package com.millionnaire.gateway.audio;

public interface AudioObjectStore {
    boolean ready();
    void put(String key, byte[] data);
    byte[] get(String key);
    default void delete(String key){throw new UnsupportedOperationException("对象存储不支持删除");}
}
