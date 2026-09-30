package io.archlens.investigation.database;

import io.archlens.investigation.InvestigationRequest.Profile;
import static io.archlens.contract.ContractException.require;

/** 可封存的业务来源与目标声明；地址、用户名和密码仅存在于独立的瞬时连接对象。 */
public record BusinessContext(String schemaVersion,Source source,Environment targetEnvironment) {
    public static final String VERSION="archlens.business-context.v1";
    public record Source(String product,String database,String declaredVersion,String connectionFingerprint) {
        public Source {
            require(java.util.Set.of("MySQL","Oracle","KingbaseES","DM").contains(product),"DATASOURCE_UNSUPPORTED","Unsupported database product");
            require(database!=null&&database.matches("[\\p{L}\\p{N}_$-]{1,64}"),"DATASOURCE_SCOPE_INVALID","Specify one database name");
            text(declaredVersion,100);
            require(connectionFingerprint==null||connectionFingerprint.matches("[a-f0-9]{64}"),"DATASOURCE_CONFIG_INVALID","Invalid source identity");
        }
        public Source bind(String hash){return new Source(product,database,declaredVersion,hash);}
    }
    public record Environment(Profile database,String compatibilityMode,Profile operatingSystem,String architecture,Profile runtime) {
        public Environment {text(compatibilityMode,100);text(architecture,60);}
    }
    public BusinessContext {
        require(VERSION.equals(schemaVersion),"UNSUPPORTED_SCHEMA","Unsupported business context");
        require(source!=null||targetEnvironment!=null,"DATASOURCE_CONFIG_INVALID","Empty business context");
    }
    private static void text(String s,int limit){require(s==null||(!s.isBlank()&&s.length()<=limit),"DATASOURCE_CONFIG_INVALID","Invalid declared value");}
}
