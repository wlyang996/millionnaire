package com.millionnaire.gateway.safety;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Official CloudRun open API; no client-supplied openid, tokens or proxy headers. */
public class WechatContentApi {
    private final URI endpoint;
    private final int timeout;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    public WechatContentApi(int timeout) { this(URI.create("http://api.weixin.qq.com/wxa/msg_sec_check"),timeout); }
    WechatContentApi(URI endpoint,int timeout) {
        this.endpoint=endpoint;this.timeout=timeout;
        http=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeout)).followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public ContentSafety.Verdict check(String openid,String content,int scene) {
        try {
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(timeout)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("openid",openid,"version",2,"scene",scene,"content",content)))).build();
            var response=http.send(request,boundedBody());
            var body=json.readTree(response.body());
            if(response.statusCode()!=200 || body==null || !body.path("errcode").isIntegralNumber() || body.path("errcode").asInt(-1)!=0) throw ContentSafety.unavailable();
            String status=switch(body.path("result").path("suggest").asText("")) {case "pass"->"PASS";case "risky"->"RISKY";case "review"->"REVIEW";default->"UNKNOWN";};
            String trace=body.path("trace_id").asText("");
            return new ContentSafety.Verdict(status,trace.length()<=128?trace:"");
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw ContentSafety.unavailable();}
        catch(Exception e){throw ContentSafety.unavailable();} // Never log text, identity or raw provider response.
    }
    private static HttpResponse.BodyHandler<byte[]> boundedBody() {
        return info -> new HttpResponse.BodySubscriber<>() {
            final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
            Flow.Subscription subscription;int received;boolean rejected;
            public CompletionStage<byte[]> getBody(){return delegate.getBody();}
            public void onSubscribe(Flow.Subscription s){subscription=s;delegate.onSubscribe(s);}
            public void onNext(List<ByteBuffer> buffers){if(rejected)return;for(var b:buffers){if(b.remaining()>32768-received){rejected=true;subscription.cancel();delegate.onError(new java.io.IOException("oversized response"));return;}received+=b.remaining();}delegate.onNext(buffers);}
            public void onError(Throwable t){delegate.onError(t);} public void onComplete(){delegate.onComplete();}
        };
    }
}
