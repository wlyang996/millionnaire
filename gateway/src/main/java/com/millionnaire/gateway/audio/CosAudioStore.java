package com.millionnaire.gateway.audio;

import com.millionnaire.gateway.characters.CosCharacterStore;
import org.springframework.stereotype.Component;

@Component
public class CosAudioStore implements AudioObjectStore {
    private final CosCharacterStore transport;
    public CosAudioStore(CosCharacterStore transport) { this.transport = transport; }
    public boolean ready() { return transport.ready(); }
    public void put(String key, byte[] data) { transport.put(key, data, "audio/wav"); }
    public byte[] get(String key) { return transport.get(key, AudioFiles.MAX_BYTES); }
}
