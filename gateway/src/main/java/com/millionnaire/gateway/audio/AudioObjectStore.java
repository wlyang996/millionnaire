package com.millionnaire.gateway.audio;

public interface AudioObjectStore {
    boolean ready();
    void put(String key, byte[] data);
    byte[] get(String key);
}
