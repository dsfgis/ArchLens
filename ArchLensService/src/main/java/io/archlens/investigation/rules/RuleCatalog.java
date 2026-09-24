package io.archlens.investigation.rules;

import io.archlens.investigation.InvestigationReport.RuleBasis;
import java.util.*;

/** 固定版本范围的规则依据。调查时离线使用，禁止临时联网取规则或由模型改写判断依据。 */
public final class RuleCatalog {
    public static final String VERSION="archlens-rules-1.1";
    private static final Map<String,RuleBasis> RULES=new LinkedHashMap<>();
    private static final String PG="https://www.postgresql.org/docs/16/";
    private static final String MY="https://dev.mysql.com/doc/refman/8.0/en/";
    private static final String OR="https://docs.oracle.com/en/database/oracle/oracle-database/19/sqlrf/";
    private static final String JLS="https://docs.oracle.com/javase/specs/jls/se21/html/jls-13.html";
    public static final String BOOT="https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide/902835141ceffe40397423e5bd095505a2b0e40e";
    static {
        add("MYSQL_UNSIGNED","MySQL 8.0.x","PostgreSQL 16.x","CREATE TABLE numeric column attribute",MY+"numeric-type-attributes.html",PG+"datatype-numeric.html");
        add("MYSQL_AUTO_INCREMENT","MySQL 8.0.x","PostgreSQL 16.x","CREATE TABLE column AUTO_INCREMENT",MY+"example-auto-increment.html",PG+"sql-createtable.html");
        add("MYSQL_BACKTICK","MySQL 8.0.x","PostgreSQL 16.x","Backtick identifier token",MY+"identifiers.html",PG+"sql-syntax-lexical.html");
        add("MYSQL_IFNULL","MySQL 8.0.x","PostgreSQL 16.x","Unqualified IFNULL call in SELECT",MY+"flow-control-functions.html",PG+"functions-conditional.html");
        add("MYSQL_SIGNED_INT","MySQL 8.0.x","PostgreSQL 16.x","Plain signed INT/INTEGER column; value range only",MY+"integer-types.html",PG+"datatype-numeric.html");
        add("ORACLE_TYPE","Oracle 19c (19.x)","PostgreSQL 16.x","CREATE TABLE NUMBER/VARCHAR2 column",OR+"Data-Types.html",PG+"datatype.html");
        add("ORACLE_DATE","Oracle 19c (19.x)","PostgreSQL 16.x","CREATE TABLE DATE column",OR+"Data-Types.html",PG+"datatype-datetime.html");
        add("ORACLE_EMPTY_STRING","Oracle 19c (19.x)","PostgreSQL 16.x","Empty string literal in DDL/SELECT",OR+"Nulls.html",PG+"sql-syntax-lexical.html");
        add("ORACLE_NVL","Oracle 19c (19.x)","PostgreSQL 16.x","Unqualified NVL call in SELECT",OR+"NVL.html",PG+"functions-conditional.html");
        add("ORACLE_NEXTVAL","Oracle 19c (19.x)","PostgreSQL 16.x","Qualified sequence NEXTVAL in SELECT",OR+"Sequence-Pseudocolumns.html",PG+"functions-sequence.html");
        add("CS_DECIMAL","C# 12.x","Java 21.x","C# decimal keyword token; lexical occurrence only","https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/floating-point-numeric-types","https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/math/BigDecimal.html");
        add("CS_UNSIGNED","C# 12.x","Java 21.x","C# uint/ulong keyword token; lexical occurrence only","https://learn.microsoft.com/en-us/dotnet/csharp/language-reference/builtin-types/integral-numeric-types","https://docs.oracle.com/javase/specs/jls/se21/html/jls-4.html");
        add("CS_AWAIT","C# 12.x","Java 21.x","await token; binding and async state machine not resolved","https://learn.microsoft.com/en-us/dotnet/csharp/asynchronous-programming/","https://docs.oracle.com/javase/specs/jls/se21/html/jls-3.html");
        add("CS_SERIALIZATION","C# 12.x","Java 21.x","JsonPropertyName attribute candidate; namespace binding unknown","https://learn.microsoft.com/en-us/dotnet/standard/serialization/system-text-json/customize-properties");
        add("CS_PROJECT_PROFILE","C# 12.x","Java 21.x","Direct .csproj/props/targets profile declarations, not evaluated MSBuild","https://learn.microsoft.com/en-us/dotnet/core/project-sdk/msbuild-props");
        add("CS_PROJECT_DEPENDENCY","C# 12.x","Java 21.x","Direct package/assembly/framework declarations, no restore or transitive resolution","https://learn.microsoft.com/en-us/dotnet/core/project-sdk/msbuild-props");
        add("CS_PROJECT_REFERENCE","C# 12.x","Java 21.x","Direct ProjectReference declaration and authorized source membership only","https://learn.microsoft.com/en-us/visualstudio/msbuild/common-msbuild-project-items");
        add("JAVA_API_CHANGE","Java 21.x","Java 21.x","Explicit before/after pair; flat public class; primitive method descriptors",JLS);
        add("JAVA_BODY_CHANGE","Java 21.x","Java 21.x","Explicit before/after method AST bodies",JLS);
        add("JAVA_STATE_CHANGE","Java 21.x","Java 21.x","Explicit before/after fields/initializers",JLS);
        add("BOOT_JAKARTA","Spring Boot 2.7.x","Spring Boot 3.0.x","Java import of migrated EE package",BOOT);
        add("BOOT_JAVA17","Spring Boot 2.7.x","Spring Boot 3.0.x","Literal Maven java.version/compiler.release below 17",BOOT);
        add("BOOT_SERVLET_DEPENDENCY","Spring Boot 2.7.x","Spring Boot 3.0.x","Direct Maven javax.servlet:javax.servlet-api dependency",BOOT);
        add("JDK_JAXB","Java 8.x","Java 17.x/21.x","Java import javax.xml.bind; classpath unresolved","https://docs.oracle.com/en/java/javase/21/migrate/removed-tools-and-components.html");
        add("HTTPCLIENT_NAMESPACE","org.apache.httpcomponents:httpclient 4.5.x","org.apache.httpcomponents.client5:httpclient5 5.2.x","Java import org.apache.http; replacement assumed, coexistence unverified",
                "https://hc.apache.org/httpcomponents-client-5.6.x/migration-guide/migration-to-classic.html",
                "https://hc.apache.org/components/httpcomponents-client-5.2.x/5.2.3/httpclient5/dependency-info.html",
                "https://hc.apache.org/httpcomponents-client-4.5.x/current/httpclient/apidocs/org/apache/http/client/HttpClient.html");
    }
    private static void add(String id,String from,String to,String facts,String...urls) {
        RULES.put(id,new RuleBasis(id,VERSION,from,to,List.of(facts),List.of(urls),id.startsWith("CS_PROJECT_")?"2026-09-22":"2026-09-17"));
    }
    public static RuleBasis get(String id) {return Objects.requireNonNull(RULES.get(id),id);}
    public static List<RuleBasis> all() {return List.copyOf(RULES.values());}
    private RuleCatalog() {}
}
