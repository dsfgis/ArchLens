package io.archlens;

import io.archlens.agent.*;
import io.archlens.contract.*;
import org.junit.jupiter.api.Test;
import java.net.http.HttpRequest;
import java.util.*;
import java.util.concurrent.*;
import java.nio.ByteBuffer;
import static org.junit.jupiter.api.Assertions.*;

/** 网关协议检查仅使用合成响应，不发真实网络请求。 */
class DeepSeekAgentModelTest {
    static String response(String args){return Json.canonical(Map.of("choices",List.of(Map.of("finish_reason","tool_calls","message",Map.of("tool_calls",List.of(Map.of("id","call_1","type","function","function",Map.of("name","list_rules","arguments",args))))))));}
    @Test void nativeToolProtocolUsesFixedEndpointAndBoundedTimeout()throws Exception {
        var model=new DeepSeekAgentModel("test-only-key",null,request->{
            assertEquals("api.deepseek.com",request.uri().getHost());assertEquals(1234,request.timeout().orElseThrow().toMillis());
            String body=body(request);var json=Json.MAPPER.readTree(body);assertTrue(json.path("tools").isArray());assertEquals("disabled",json.path("thinking").path("type").asText());
            assertFalse(body.contains("test-only-key"));return new DeepSeekAgentModel.Reply(200,response("{}"));
        });
        assertEquals("list_rules",model.next(List.of(Map.of("role","user","content","synthetic")),AgentTools.schemas(),1234).name());
    }
    @Test void malformedAndDuplicateArgumentsAreRejected() {
        for(String body:List.of("not json",response("{\"a\":1,\"a\":2}"),response("{} garbage"),response("[]"),"x".repeat(256001),"{\"choices\":[]}")) {
            var model=new DeepSeekAgentModel("test",null,r->new DeepSeekAgentModel.Reply(200,body));
            ContractTest.assertCode("INVALID_MODEL_OUTPUT",()->model.next(List.of(),AgentTools.schemas(),1000));
        }
    }
    @Test void serviceErrorsNeverEchoRemoteBodyOrCredentials() {
        for(var entry:Map.of(401,"MODEL_AUTH_FAILED",402,"MODEL_BALANCE_INSUFFICIENT",429,"MODEL_RATE_LIMITED",500,"MODEL_UNAVAILABLE").entrySet()) {
            var model=new DeepSeekAgentModel("secret",null,r->new DeepSeekAgentModel.Reply(entry.getKey(),"private response"));
            var error=assertThrows(ContractException.class,()->model.next(List.of(),AgentTools.schemas(),1000));
            assertEquals(entry.getValue(),error.code());assertFalse(error.getMessage().contains("private"));assertFalse(error.getMessage().contains("secret"));
        }
    }
    @Test void timeoutAndMissingKeyHaveStableCodes() {
        ContractTest.assertCode("MODEL_NOT_CONFIGURED",()->new DeepSeekAgentModel(null,null));
        ContractTest.assertCode("MODEL_TIMEOUT",()->new DeepSeekAgentModel("test",null,r->{throw new java.net.http.HttpTimeoutException("private");}).next(List.of(),AgentTools.schemas(),1000));
    }
    static String body(HttpRequest request)throws Exception {
        var result=new CompletableFuture<String>();var bytes=new java.io.ByteArrayOutputStream();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription s){s.request(Long.MAX_VALUE);}public void onNext(ByteBuffer b){byte[] a=new byte[b.remaining()];b.get(a);bytes.writeBytes(a);}
            public void onError(Throwable e){result.completeExceptionally(e);}public void onComplete(){result.complete(bytes.toString(java.nio.charset.StandardCharsets.UTF_8));}
        });return result.get(2,TimeUnit.SECONDS);
    }
}
