package io.archlens.storage;

import java.net.URI;
import java.sql.*;
import java.util.*;
import org.neo4j.driver.*;
import java.util.concurrent.TimeUnit;
import static io.archlens.contract.ContractException.require;

/** Backend-only environment configuration. Never serialize or log this object. */
public final class StorageConfig {
    private final String pgUrl,pgUser,pgPassword,neoUri,neoUser,neoPassword,neoDatabase;
    public StorageConfig(Map<String,String> env) {
        pgUrl=value(env,"ARCHLENS_PG_URL"); pgUser=value(env,"ARCHLENS_PG_USER"); pgPassword=value(env,"ARCHLENS_PG_PASSWORD");
        neoUri=value(env,"ARCHLENS_NEO4J_URI");neoUser=value(env,"ARCHLENS_NEO4J_USER");neoPassword=value(env,"ARCHLENS_NEO4J_PASSWORD");
        neoDatabase=value(env,"ARCHLENS_NEO4J_DATABASE");
        require(pgUrl.startsWith("jdbc:postgresql://"),"STORAGE_CONFIG","Expected PostgreSQL JDBC URL");
        validate(URI.create(pgUrl.substring(5)),Set.of("postgresql"),true);
        validate(URI.create(neoUri),Set.of("bolt","bolt+s","neo4j","neo4j+s"),false);
    }
    private static String value(Map<String,String> env,String key) {
        String value=env.get(key); require(value!=null&&!value.isBlank(),"STORAGE_CONFIG","Missing backend storage configuration: "+key); return value;
    }
    private static void validate(URI uri,Set<String> schemes,boolean database) {
        require(schemes.contains(uri.getScheme()) && uri.getHost()!=null && uri.getUserInfo()==null
                && uri.getQuery()==null && uri.getFragment()==null && (!database || (uri.getPath()!=null&&uri.getPath().length()>1)),
                "STORAGE_CONFIG","Invalid storage address; keep credentials separate and omit query parameters");
    }
    public Connection connect() throws SQLException {
        Properties p=new Properties();p.setProperty("user",pgUser);p.setProperty("password",pgPassword);
        p.setProperty("connectTimeout","5");p.setProperty("socketTimeout","20");p.setProperty("ApplicationName","ArchLens");
        p.setProperty("options","-c statement_timeout=15000 -c lock_timeout=5000");
        return DriverManager.getConnection(pgUrl,p);
    }
    public org.neo4j.driver.Driver driver() {
        return GraphDatabase.driver(neoUri,AuthTokens.basic(neoUser,neoPassword),Config.builder()
                .withConnectionTimeout(5,TimeUnit.SECONDS).withConnectionAcquisitionTimeout(10,TimeUnit.SECONDS)
                .withMaxTransactionRetryTime(5,TimeUnit.SECONDS).withLogging(Logging.none()).build());
    }
    public String neoDatabase() { return neoDatabase; }
    @Override public String toString() { return "StorageConfig[REDACTED]"; }
}
