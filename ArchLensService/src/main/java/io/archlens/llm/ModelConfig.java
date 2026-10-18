package io.archlens.llm;

import io.archlens.contract.ContractException;
import java.net.URI;
import java.util.Map;
import static io.archlens.contract.ContractException.require;

/** Backend-only configuration for a Chat Completions compatible service. */
public final class ModelConfig {
    private final String provider, model, apiKey;
    private final URI endpoint;
    private final boolean legacyDeepSeek;

    public ModelConfig(String provider, String endpoint, String model, String apiKey) {
        this(provider, endpoint, model, apiKey, false);
    }
    private ModelConfig(String provider, String endpoint, String model, String apiKey, boolean legacy) {
        require(provider != null && provider.matches("[A-Za-z0-9._-]{1,80}"), "INVALID_MODEL_CONFIG", "Invalid provider label");
        require(model != null && model.matches("[A-Za-z0-9][A-Za-z0-9._:/@+-]{0,199}"), "INVALID_MODEL_CONFIG", "Configure the model identifier");
        URI uri;
        try { uri = URI.create(endpoint); } catch (Exception e) { throw new ContractException("INVALID_MODEL_CONFIG", "Invalid model endpoint"); }
        require(uri.getHost()!=null && uri.getRawUserInfo()==null && uri.getRawQuery()==null && uri.getRawFragment()==null,
                "INVALID_MODEL_CONFIG", "Endpoint must not contain credentials, query or fragment");
        boolean loopback = java.util.Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost());
        require("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) && loopback,
                "INVALID_MODEL_CONFIG", "Use HTTPS, or HTTP for a loopback model server");
        require(apiKey == null || !apiKey.contains("\r") && !apiKey.contains("\n"), "INVALID_MODEL_CONFIG", "Invalid API key");
        require(loopback || apiKey != null && !apiKey.isBlank(), "MODEL_NOT_CONFIGURED", "Configure the backend API key");
        this.provider=provider; this.endpoint=uri; this.model=model; this.apiKey=apiKey; this.legacyDeepSeek=legacy;
    }
    public static ModelConfig fromEnvironment(Map<String,String> env) {
        if ("true".equalsIgnoreCase(env.get("ARCHLENS_LLM_DISABLED"))) return null;
        boolean generic = java.util.List.of("ARCHLENS_LLM_PROVIDER", "ARCHLENS_LLM_ENDPOINT", "ARCHLENS_LLM_MODEL", "ARCHLENS_LLM_API_KEY")
                .stream().anyMatch(k -> env.get(k)!=null && !env.get(k).isBlank());
        if (generic) return new ModelConfig(env.getOrDefault("ARCHLENS_LLM_PROVIDER", "compatible"),
                env.get("ARCHLENS_LLM_ENDPOINT"), env.get("ARCHLENS_LLM_MODEL"), env.get("ARCHLENS_LLM_API_KEY"));
        String key=env.get("DEEPSEEK_API_KEY");
        return key==null || key.isBlank() ? null : legacyDeepSeek(key, env.get("DEEPSEEK_MODEL"));
    }
    public static ModelConfig legacyDeepSeek(String key,String model) {
        require(key!=null&&!key.isBlank(),"MODEL_NOT_CONFIGURED","Configure the backend API key");
        return new ModelConfig("deepseek",DeepSeekGateway.ENDPOINT.toString(),
                model==null||model.isBlank()?DeepSeekGateway.DEFAULT_MODEL:model,key,true);
    }
    public String provider(){return provider;}
    public String model(){return model;}
    public URI endpoint(){return endpoint;}
    public String apiKey(){return apiKey;}
    public boolean legacyDeepSeek(){return legacyDeepSeek;}
    @Override public String toString(){return "ModelConfig["+provider+", "+model+"]";}
}
