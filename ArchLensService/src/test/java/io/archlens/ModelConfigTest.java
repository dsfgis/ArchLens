package io.archlens;

import io.archlens.agent.*;
import io.archlens.llm.ModelConfig;
import io.archlens.contract.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelConfigTest {
    @Test void genericConfigurationOverridesLegacyWithoutLeakingCredentials()throws Exception {
        var config=ModelConfig.fromEnvironment(Map.of("ARCHLENS_LLM_PROVIDER","custom","ARCHLENS_LLM_ENDPOINT","https://models.example/v1/chat/completions","ARCHLENS_LLM_MODEL","org/model:tag","ARCHLENS_LLM_API_KEY","synthetic-secret","DEEPSEEK_API_KEY","old-secret"));
        var client=new ConfiguredAgentModel(config,r->{
            assertEquals("models.example",r.uri().getHost());assertEquals("Bearer synthetic-secret",r.headers().firstValue("Authorization").orElseThrow());
            var body=Json.MAPPER.readTree(DeepSeekAgentModelTest.body(r));assertEquals("org/model:tag",body.path("model").asText());
            assertFalse(body.has("thinking"));assertFalse(body.path("parallel_tool_calls").asBoolean());assertFalse(body.toString().contains("secret"));
            return new ConfiguredAgentModel.Reply(200,DeepSeekAgentModelTest.response("{}"));
        });
        assertEquals("list_rules",client.next(List.of(),AgentTools.schemas(),500).name());assertFalse(config.toString().contains("secret"));
    }
    @Test void loopbackModelCanBeKeylessAndInvalidConfigDoesNotSilentlyFallBack()throws Exception {
        var c=new ModelConfig("local","http://127.0.0.1:11434/v1/chat/completions","local-model",null);
        new ConfiguredAgentModel(c,r->{assertTrue(r.headers().firstValue("Authorization").isEmpty());return new ConfiguredAgentModel.Reply(200,DeepSeekAgentModelTest.response("{}"));}).next(List.of(),AgentTools.schemas(),1000);
        ContractTest.assertCode("INVALID_MODEL_CONFIG",()->ModelConfig.fromEnvironment(Map.of("ARCHLENS_LLM_MODEL","chosen","DEEPSEEK_API_KEY","legacy")));
        for(String endpoint:List.of("http://external.example/v1/chat/completions","https://user:secret@example.com/v1","https://example.com/v1?key=secret","https://example.com/v1#fragment"))
            ContractTest.assertCode("INVALID_MODEL_CONFIG",()->new ModelConfig("test",endpoint,"model","key"));
        assertNull(ModelConfig.fromEnvironment(Map.of()));assertNull(ModelConfig.fromEnvironment(Map.of("ARCHLENS_LLM_DISABLED","true","DEEPSEEK_API_KEY","key")));
    }
    @Test void configuredClientUsesRealLoopbackHttpTransport()throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var received=new java.util.concurrent.CompletableFuture<String>();
        server.createContext("/v1/chat/completions",exchange->{
            received.complete(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes=DeepSeekAgentModelTest.response("{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });
        server.start();
        try {
            var config=new ModelConfig("local","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/chat/completions","fixture/model",null);
            assertEquals("list_rules",new ConfiguredAgentModel(config).next(List.of(Map.of("role","user","content","合成目标")),AgentTools.schemas(),5000).name());
            var body=Json.MAPPER.readTree(received.get(5,java.util.concurrent.TimeUnit.SECONDS));assertEquals("fixture/model",body.path("model").asText());
            assertEquals("合成目标",body.path("messages").get(0).path("content").asText());
        }finally{server.stop(0);}
    }
    @Test void configurableTransportPreservesTimeoutAuthAndFormatErrors() {
        var c=new ModelConfig("custom","https://models.example/chat/completions","m","key");
        for(var status:Map.of(401,"MODEL_AUTH_FAILED",403,"MODEL_AUTH_FAILED",429,"MODEL_RATE_LIMITED",503,"MODEL_UNAVAILABLE").entrySet()) {
            var error=assertThrows(ContractException.class,()->new ConfiguredAgentModel(c,r->new ConfiguredAgentModel.Reply(status.getKey(),"secret remote body")).next(List.of(),AgentTools.schemas(),1000));
            assertEquals(status.getValue(),error.code());assertFalse(error.getMessage().contains("secret"));
        }
        ContractTest.assertCode("MODEL_TIMEOUT",()->new ConfiguredAgentModel(c,r->{throw new java.net.http.HttpTimeoutException("secret");}).next(List.of(),AgentTools.schemas(),1000));
        ContractTest.assertCode("INVALID_MODEL_OUTPUT",()->new ConfiguredAgentModel(c,r->new ConfiguredAgentModel.Reply(200,"not-json")).next(List.of(),AgentTools.schemas(),1000));
    }
}
