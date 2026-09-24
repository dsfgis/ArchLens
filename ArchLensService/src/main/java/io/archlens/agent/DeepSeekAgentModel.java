package io.archlens.agent;

import io.archlens.contract.*;
import io.archlens.llm.DeepSeekGateway;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import static io.archlens.contract.ContractException.require;

/** DeepSeek 原生 tool_calls 网关。固定官方端点、限制响应字节，异常不回显远端响应或密钥。 */
public final class DeepSeekAgentModel implements AgentModel {
    public record Reply(int status,String body) {}
    @FunctionalInterface public interface Transport {Reply send(HttpRequest request) throws Exception;}
    private final String key,model;private final Transport transport;
    public DeepSeekAgentModel(String key,String model){this(key,model,DeepSeekAgentModel::send);}
    public DeepSeekAgentModel(String key,String model,Transport transport){
        require(key!=null&&!key.isBlank()&&!key.contains("\r")&&!key.contains("\n"),"MODEL_NOT_CONFIGURED","Backend model key is missing");
        this.key=key;this.model=model==null||model.isBlank()?DeepSeekGateway.DEFAULT_MODEL:model;
        require(this.model.matches("[A-Za-z0-9._-]{1,80}"),"INVALID_MODEL_CONFIG","Invalid backend model name");this.transport=Objects.requireNonNull(transport);
    }
    @Override public Call next(List<Map<String,Object>> messages,List<Map<String,Object>> tools,long timeoutMillis) {
        try {
            require(timeoutMillis>0,"AGENT_TIME_BUDGET","No remaining model time");
            var body=Map.of("model",model,"messages",messages,"tools",tools,"tool_choice","auto","stream",false,
                    "thinking",Map.of("type","disabled"),"max_tokens",3000);
            String json=Json.canonical(body);require(json.length()<=180_000,"AGENT_CONTEXT_BUDGET","Model context exceeds budget");
            var req=HttpRequest.newBuilder(DeepSeekGateway.ENDPOINT).timeout(Duration.ofMillis(Math.min(60000,timeoutMillis)))
                    .header("Authorization","Bearer "+key).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json,StandardCharsets.UTF_8)).build();
            var reply=transport.send(req);
            if(reply.status()!=200)throw new ContractException(switch(reply.status()) {case 401,403->"MODEL_AUTH_FAILED";case 402->"MODEL_BALANCE_INSUFFICIENT";case 429->"MODEL_RATE_LIMITED";default->"MODEL_UNAVAILABLE";},"Model service failed");
            require(reply.body()!=null&&reply.body().length()<=256_000,"INVALID_MODEL_OUTPUT","Response exceeds budget");
            var root=Json.MAPPER.readTree(reply.body());var choices=root.path("choices");
            require(choices.isArray()&&choices.size()==1,"INVALID_MODEL_OUTPUT","Expected one model choice");
            var choice=choices.get(0);var message=choice.path("message");var calls=message.path("tool_calls");
            require("tool_calls".equals(choice.path("finish_reason").asText())&&calls.isArray()&&calls.size()==1,"INVALID_MODEL_OUTPUT","Expected one complete tool call");
            var call=calls.get(0);String id=call.path("id").asText(),name=call.path("function").path("name").asText();
            require("function".equals(call.path("type").asText())&&id.matches("[A-Za-z0-9_-]{1,100}")&&name.matches("[a-z_]{1,60}"),"INVALID_MODEL_OUTPUT","Invalid tool identity");
            var args=call.path("function").path("arguments");require(args.isTextual()&&args.textValue().length()<=24000,"INVALID_MODEL_OUTPUT","Invalid tool arguments");
            var parsed=Json.MAPPER.readTree(args.textValue());require(parsed!=null&&parsed.isObject(),"INVALID_MODEL_OUTPUT","Arguments must be an object");
            return new Call(id,name,parsed);
        }catch(ContractException e){throw e;}
        catch(HttpTimeoutException|TimeoutException e){throw new ContractException("MODEL_TIMEOUT","Model call timed out");}
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new ContractException("MODEL_INTERRUPTED","Model call interrupted");}
        catch(Exception e){throw new ContractException("INVALID_MODEL_OUTPUT","Model transport or output validation failed");}
    }
    private static Reply send(HttpRequest request)throws Exception {
        try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var future=client.sendAsync(request,info->new LimitedSubscriber());
            try {var reply=future.get(request.timeout().orElseThrow().toMillis(),TimeUnit.MILLISECONDS);return new Reply(reply.statusCode(),new String(reply.body(),StandardCharsets.UTF_8));}
            finally {future.cancel(true);}
        }
    }
    /** 在接收阶段中止超大响应，避免先完整载入再检查长度。 */
    private static final class LimitedSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body=new CompletableFuture<>();private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody(){return body;}
        public void onSubscribe(Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<ByteBuffer> items){
            for(var item:items){if(bytes.size()+item.remaining()>256000){subscription.cancel();body.completeExceptionally(new IllegalStateException("Response limit"));return;}
                byte[] part=new byte[item.remaining()];item.get(part);bytes.writeBytes(part);}
            subscription.request(1);
        }
        public void onError(Throwable error){body.completeExceptionally(error);}
        public void onComplete(){body.complete(bytes.toByteArray());}
    }
}
