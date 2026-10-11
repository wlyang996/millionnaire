package com.millionnaire.gateway.characters;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qcloud.cos.COSClient;
import com.qcloud.cos.auth.COSSessionCredentials;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.*;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.net.*;
import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

class WechatStorageTest {
    HttpServer server;
    WechatStorageApi api;
    final ObjectMapper json = new ObjectMapper();
    final AtomicInteger authCalls = new AtomicInteger();
    final AtomicReference<String> authResponse = new AtomicReference<>();
    final AtomicReference<String> metaResponse = new AtomicReference<>("{\"errcode\":0,\"respdata\":{\"x_cos_meta_field_strs\":[\"signed-metadata\"]}}");
    final AtomicReference<String> metaRequest = new AtomicReference<>();
    final AtomicReference<String> authMethod = new AtomicReference<>();
    final AtomicInteger authStatus = new AtomicInteger(200);
    static final class TestClock extends Clock {
        long seconds = 1000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochSecond(seconds); }
    }
    @BeforeEach void start() throws Exception {
        authResponse.set(credentials(2000));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/_/cos/getauth", exchange -> {
            authCalls.incrementAndGet(); authMethod.set(exchange.getRequestMethod());
            byte[] bytes = authResponse.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(authStatus.get(),bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.createContext("/_/cos/metaid/encode", exchange -> {
            metaRequest.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = metaResponse.get().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); api = new WechatStorageApi(URI.create("http://127.0.0.1:"+server.getAddress().getPort()));
    }
    @AfterEach void stop() { server.stop(0); }
    String credentials(long expiration) { return "{\"TmpSecretId\":\"temporary-id\",\"TmpSecretKey\":\"private-test-key\",\"Token\":\"private-test-token\",\"ExpiredTime\":\""+expiration+"\"}"; }

    @Test void noPermanentKeysAreNeededAndCredentialsRefreshBeforeExpiry() {
        var clock = new TestClock(); var provider = new WechatCosCredentials(api,clock);
        var first = provider.getCredentials();
        assertThat(first).isInstanceOf(COSSessionCredentials.class);
        assertThat(((COSSessionCredentials) first).getSessionToken()).isEqualTo("private-test-token");
        assertThat(provider.getCredentials()).isSameAs(first); assertThat(authCalls).hasValue(1); assertThat(authMethod.get()).isEqualTo("GET");
        clock.seconds = 1940; authResponse.set(credentials(3000));
        assertThat(provider.getCredentials()).isNotSameAs(first); assertThat(authCalls).hasValue(2);
        provider.refresh(); provider.getCredentials(); assertThat(authCalls).hasValue(3);
    }
    @Test void concurrentRequestsShareOneCredentialFetch() throws Exception {
        var provider = new WechatCosCredentials(api,new TestClock());
        try (var pool = Executors.newFixedThreadPool(8)) {
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int i=0;i<8;i++) tasks.add(pool.submit(provider::getCredentials));
            for (var task : tasks) task.get(3,TimeUnit.SECONDS);
            assertThat(authCalls).hasValue(1);
        }
    }
    @Test void expiredIncompleteAndDeniedResponsesNeverExposeSecretsOrUseStaleCredentials() {
        var clock = new TestClock(); var provider = new WechatCosCredentials(api,clock);
        provider.getCredentials(); clock.seconds = 1941; authResponse.set(credentials(1941));
        assertThatThrownBy(provider::getCredentials).hasMessageContaining("已过期").hasMessageNotContaining("private-test");
        authResponse.set("{\"TmpSecretId\":\"temporary-id\",\"ExpiredTime\":3000}");
        assertThatThrownBy(provider::getCredentials).hasMessageContaining("不完整");
        authResponse.set("{\"errcode\":403,\"errmsg\":\"private-test-key\"}");
        assertThatThrownBy(provider::getCredentials).hasMessageContaining("云调用").hasMessageNotContaining("private-test-key");
        authResponse.set("not-json private-test-token");
        assertThatThrownBy(provider::getCredentials).hasMessageNotContaining("private-test-token");
        authStatus.set(403);
        assertThatThrownBy(provider::getCredentials).hasMessageContaining("临时凭证获取失败");
    }
    @Test void uploadsAttachAdminMetadataAndDownloadsUseTheSameCredentials() throws Exception {
        var clock = new TestClock(); var receivedMeta = new AtomicReference<ObjectMetadata>(); var puts = new AtomicInteger();
        byte[] png = CharacterCatalogTest.png(true,64,64);
        var store = new CosCharacterStore("bucket-123","ap-shanghai",api,clock,(provider,config) -> new COSClient(provider,config) {
            @Override public PutObjectResult putObject(String bucket, String key, InputStream body, ObjectMetadata metadata) {
                provider.getCredentials(); receivedMeta.set(metadata); puts.incrementAndGet();
                try { assertThat(body.readAllBytes()).isEqualTo(png); } catch (IOException e) { throw new RuntimeException(e); }
                return new PutObjectResult();
            }
            @Override public COSObject getObject(String bucket,String key) {
                provider.getCredentials(); var result = new COSObject(); result.setObjectContent(new COSObjectInputStream(new ByteArrayInputStream(png),new org.apache.http.client.methods.HttpGet())); return result;
            }
        });
        try {
            assertThat(store.ready()).isTrue(); store.verify();
            store.put("characters/9/pack/avatar.png",png);
            assertThat(receivedMeta.get().getUserMetadata()).containsEntry("fileid","signed-metadata");
            var sent = json.readTree(metaRequest.get());
            assertThat(sent.path("openid").asText()).isEmpty(); assertThat(sent.path("bucket").asText()).isEqualTo("bucket-123");
            assertThat(sent.path("paths").get(0).asText()).isEqualTo("characters/9/pack/avatar.png");
            assertThat(store.get("characters/9/pack/avatar.png")).isEqualTo(png); assertThat(authCalls).hasValue(1);
            metaResponse.set("{\"errcode\":0,\"respdata\":{}}");
            assertThatThrownBy(() -> store.put("characters/9/pack/pawn.png",png)).hasMessageContaining("元数据");
            assertThat(puts).hasValue(1);
        } finally { store.close(); }
    }
    @Test void tokenExpiryRetriesOnceButPermissionDenialDoesNotRetry() {
        var calls = new AtomicInteger(); var denied = new AtomicBoolean();
        var store = new CosCharacterStore("bucket-123","ap-shanghai",api,new TestClock(),(provider,config) -> new COSClient(provider,config) {
            @Override public COSObject getObject(String bucket,String key) {
                provider.getCredentials(); int count = calls.incrementAndGet();
                if (count == 1 || denied.get()) { var e = new CosServiceException("private-test-token"); e.setErrorCode(denied.get() ? "AccessDenied" : "ExpiredToken"); throw e; }
                var result = new COSObject(); result.setObjectContent(new COSObjectInputStream(new ByteArrayInputStream(new byte[]{1}),new org.apache.http.client.methods.HttpGet())); return result;
            }
        });
        try {
            assertThat(store.get("test")).containsExactly((byte)1); assertThat(calls).hasValue(2); assertThat(authCalls).hasValue(2);
            denied.set(true);
            assertThatThrownBy(() -> store.get("test")).hasMessageContaining("读取失败").hasMessageNotContaining("private-test-token");
            assertThat(calls).hasValue(3);
        } finally { store.close(); }
    }
    @Test void missingBucketOrRegionDoesNotFetchAnyCredentials() {
        var store = new CosCharacterStore("","ap-shanghai",api,new TestClock());
        assertThat(store.ready()).isFalse();
        assertThatThrownBy(store::verify).hasMessageContaining("无需配置长期密钥");
        assertThat(authCalls).hasValue(0); store.close();
    }
    @Test void realSdkSignsUploadsWithTemporaryTokenAndWritesFileMetadataHeader() throws Exception {
        byte[] png = CharacterCatalogTest.png(true,64,64);
        var uploaded = new AtomicReference<byte[]>(); var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
        server.createContext("/characters/", exchange -> {
            if (exchange.getRequestMethod().equals("PUT")) {
                uploaded.set(exchange.getRequestBody().readAllBytes()); headers.set(exchange.getRequestHeaders());
                exchange.sendResponseHeaders(200,-1);
            } else {
                exchange.sendResponseHeaders(200,png.length); exchange.getResponseBody().write(png);
            }
            exchange.close();
        });
        var store = new CosCharacterStore("bucket-123","ap-shanghai",api,new TestClock(),(provider,config) -> {
            config.setHttpProtocol(com.qcloud.cos.http.HttpProtocol.http);
            config.setEndpointBuilder(new com.qcloud.cos.endpoint.EndpointBuilder() {
                public String buildGeneralApiEndpoint(String bucket) { return "127.0.0.1:"+server.getAddress().getPort(); }
                public String buildGetServiceApiEndpoint() { return buildGeneralApiEndpoint(""); }
            });
            return new COSClient(provider,config);
        });
        try {
            store.put("characters/9/avatar.png",png);
            assertThat(uploaded.get()).isEqualTo(png);
            assertThat(headers.get().getFirst("x-cos-security-token")).isEqualTo("private-test-token");
            assertThat(headers.get().getFirst("x-cos-meta-fileid")).isEqualTo("signed-metadata");
            assertThat(headers.get().getFirst("Authorization")).contains("temporary-id").doesNotContain("private-test-key");
            assertThat(authCalls).hasValue(1);
        } finally { store.close(); }
    }
    @Test void oversizedResponsesAndMissingMetadataAreRejected() {
        authResponse.set("x".repeat(40000));
        assertThatThrownBy(api::credentials).hasMessageContaining("临时凭证获取失败");
        metaResponse.set("{\"errcode\":403,\"errmsg\":\"private-test-key\"}");
        assertThatThrownBy(() -> api.metadata("bucket-123","characters/file.png")).hasMessageContaining("元数据").hasMessageNotContaining("private-test-key");
    }
}
