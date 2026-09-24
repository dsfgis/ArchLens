package io.archlens;

import io.archlens.contract.Json;
import io.archlens.investigation.*;
import io.archlens.investigation.rules.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static io.archlens.investigation.InvestigationRequest.*;
import static org.junit.jupiter.api.Assertions.*;

/** 从真实源码片段验证规则、反例和证据；不运行这些被分析的项目。 */
class ScenarioRulesTest {
    @TempDir Path root;
    void file(String path,String text)throws Exception {Path p=root.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,text);}
    InvestigationRequest request(Scenario scenario,String source,String sv,String target,String tv,String...files) {
        return new InvestigationRequest(VERSION,scenario,new Profile(source,sv),new Profile(target,tv),"只读调查",
                List.of(),List.of("保留金额和异常语义"),List.of(files),null,Budget.defaults());
    }
    InvestigationReport run(InvestigationRequest request)throws Exception{return new InvestigationEngine().investigate(root,request,()->false);}
    InvestigationReport db(String product,String version,String sql)throws Exception {
        file("input.sql",sql);return run(request(Scenario.DATABASE_MIGRATION,product,version,"PostgreSQL","16.15","input.sql"));
    }
    List<InvestigationReport.Finding> rules(InvestigationReport r){return r.findings().stream().filter(f->f.rule()!=null).toList();}
    InvestigationReport.Finding finding(InvestigationReport r,String id) {
        return rules(r).stream().filter(f->f.rule().ruleId().equals(id)).findFirst().orElseThrow(()->new AssertionError(id+": "+Json.canonical(r)));
    }
    void gap(InvestigationReport r,String code){assertTrue(r.coverageGaps().stream().anyMatch(g->g.code().equals(code)),code);}
    @Test void csharpProjectMetadataReferencesAndSourceFeaturesShareEvidence()throws Exception {
        file("app.csproj","<Project Sdk='Microsoft.NET.Sdk'><PropertyGroup><TargetFramework>net8.0</TargetFramework><LangVersion>12</LangVersion></PropertyGroup><ItemGroup><PackageReference Include='Some.Package' Version='1.0'/><ProjectReference Include='lib/lib.csproj'/></ItemGroup></Project>");
        file("lib/lib.csproj","<Project/>");file("Payment.cs","public class Payment { public decimal Amount; }");
        var r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","12","Java","21","app.csproj","lib/lib.csproj","Payment.cs"));
        for(String id:List.of("CS_PROJECT_PROFILE","CS_PROJECT_DEPENDENCY","CS_PROJECT_REFERENCE"))assertEquals("UNKNOWN",finding(r,id).outcome());
        assertEquals("INCOMPATIBLE",finding(r,"CS_DECIMAL").outcome());assertEquals(3,r.sources().size());
        assertFalse(r.coverageGaps().stream().anyMatch(g->g.code().equals("CS_PROJECT_REFERENCE_UNCOLLECTED")));
        var ev=finding(r,"CS_PROJECT_DEPENDENCY").evidence().getFirst();
        assertEquals(r.sources().stream().filter(src->src.path().equals("app.csproj")).findFirst().orElseThrow().sha256(),ev.sourceHash());
        assertTrue(finding(r,"CS_PROJECT_DEPENDENCY").summary().contains("Some.Package"));
    }
    @Test void csharpXmlRejectsEntitiesAndDoesNotReadReferences()throws Exception {
        file("app.csproj","<!DOCTYPE Project [<!ENTITY x SYSTEM 'file:///not-authorized'>]><Project>&x;</Project>");
        var r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","12","Java","21","app.csproj"));gap(r,"CS_PROJECT_XML_REJECTED");assertTrue(rules(r).isEmpty());
        file("app.csproj","<Project><ItemGroup><ProjectReference Include='../outside.csproj'/><ProjectReference Include='$(Secret)/x.csproj'/></ItemGroup></Project>");
        r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","12","Java","21","app.csproj"));gap(r,"CS_PROJECT_REFERENCE_UNCOLLECTED");assertEquals(1,r.sources().size());
    }
    @Test void csharpDeclaredLanguageConflictWithholdsAllFindingsButConditionalValuesStayUnknown()throws Exception {
        file("app.csproj","<Project><PropertyGroup><LangVersion>11</LangVersion></PropertyGroup></Project>");file("x.cs","class X { decimal value; }");
        var r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","12","Java","21","app.csproj","x.cs"));gap(r,"PROFILE_SOURCE_VERSION_CONFLICT");assertTrue(rules(r).isEmpty());
        file("app.csproj","<Project><PropertyGroup Condition='unresolved'><LangVersion>11</LangVersion></PropertyGroup></Project>");
        r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","12","Java","21","app.csproj","x.cs"));gap(r,"CS_CONDITIONAL_DECLARATION");assertEquals("INCOMPATIBLE",finding(r,"CS_DECIMAL").outcome());
    }
    @Test void csharpUnsupportedVersionsDoNotApplyProjectRules()throws Exception {
        file("app.csproj","<Project><PropertyGroup><TargetFramework>net9.0</TargetFramework></PropertyGroup></Project>");
        var r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","13","Java","21","app.csproj"));assertTrue(rules(r).isEmpty());gap(r,"SCENARIO_RULES_UNAVAILABLE");
    }
    @Test void mysqlChecksNativeSyntaxAndNarrowCompatibleRangeWithOriginalUtf8Evidence()throws Exception {
        String sql="-- 中文😀\r\nCREATE TABLE `订单` (id BIGINT UNSIGNED AUTO_INCREMENT, plain INT);\r\nSELECT IFNULL(plain, 0) FROM `订单`;";
        var r=db("MySQL","8.0.36",sql);
        for(String rule:List.of("MYSQL_UNSIGNED","MYSQL_AUTO_INCREMENT","MYSQL_BACKTICK","MYSQL_IFNULL"))assertEquals("INCOMPATIBLE",finding(r,rule).outcome());
        assertEquals("COMPATIBLE",finding(r,"MYSQL_SIGNED_INT").outcome());
        var ev=finding(r,"MYSQL_UNSIGNED").evidence().getFirst();byte[] raw=sql.getBytes(StandardCharsets.UTF_8);
        assertEquals("UNSIGNED",new String(Arrays.copyOfRange(raw,ev.location().byteOffset().intValue(),(int)(ev.location().byteOffset()+ev.location().byteLength())),StandardCharsets.UTF_8));
        assertEquals(2,ev.location().startLine());assertEquals(r.sources().getFirst().sourceId(),ev.sourceId());assertEquals(Json.sha256(raw),ev.sourceHash());
        assertEquals(Json.hash(r),Json.hash(Json.MAPPER.readValue(Json.canonical(r),InvestigationReport.class)));
    }
    @Test void mysqlCommentsStringsAndColumnNamesAreNotAttributes()throws Exception {
        var r=db("MySQL","8.0","-- AUTO_INCREMENT UNSIGNED\nCREATE TABLE t (unsigned INT, note VARCHAR(80) DEFAULT 'IFNULL(`x`) AUTO_INCREMENT'); SELECT 'IFNULL(x)' FROM t;");
        assertFalse(rules(r).stream().anyMatch(f->Set.of("MYSQL_UNSIGNED","MYSQL_AUTO_INCREMENT","MYSQL_IFNULL","MYSQL_BACKTICK").contains(f.rule().ruleId())));
    }
    @Test void oracleTypesDateEmptyStringsAndSequenceCallsHaveScopedOutcomes()throws Exception {
        var r=db("Oracle","19c","CREATE TABLE t (id NUMBER(19), name VARCHAR2(40 CHAR), created DATE, label VARCHAR2(5) DEFAULT ''); SELECT NVL(name, ''), seq.NEXTVAL FROM t;");
        assertEquals("INCOMPATIBLE",finding(r,"ORACLE_TYPE").outcome());assertEquals("CONDITIONAL",finding(r,"ORACLE_DATE").outcome());
        assertEquals("CONDITIONAL",finding(r,"ORACLE_EMPTY_STRING").outcome());assertEquals("INCOMPATIBLE",finding(r,"ORACLE_NVL").outcome());assertEquals("INCOMPATIBLE",finding(r,"ORACLE_NEXTVAL").outcome());
    }
    @Test void oracleQuotedNamesAndQualifiedUserFunctionsDoNotPretendBuiltinBinding()throws Exception {
        var r=db("Oracle","19.0","CREATE TABLE t (\"DATE\" INTEGER); SELECT custom.NVL(x, y), 's.NEXTVAL', \"NVL\" FROM t;");
        assertTrue(rules(r).isEmpty());
    }
    @Test void proceduresAndExecutableCommentsRemainUnknown()throws Exception {
        var procedure=db("Oracle","19c","CREATE OR REPLACE PACKAGE BODY p AS PROCEDURE f IS BEGIN SELECT NVL(a,'') INTO b FROM t; END; END;");
        assertTrue(rules(procedure).isEmpty());gap(procedure,"PROCEDURAL_SQL_UNSUPPORTED");
        var executable=db("MySQL","8.0","CREATE TABLE t (id INT); /*!80000 SELECT IFNULL(x,0) FROM t */");
        assertTrue(rules(executable).isEmpty());gap(executable,"EXECUTABLE_COMMENT_UNSUPPORTED");
    }
    @Test void unsupportedSqlModesAndMalformedInputsDiscardAllFileMatches()throws Exception {
        for(String sql:List.of("CREATE TABLE t (id INT); SELECT \"IFNULL\" FROM t;","CREATE TABLE t (id INT); SELECT 'a\\'b';","CREATE TABLE t (id INT","CREATE TABLE t (id INT); /*")) {
            assertTrue(rules(db("MySQL","8.0",sql)).isEmpty());
        }
        assertTrue(rules(db("Oracle","19c","SELECT q'[NVL(x, '')]' FROM dual;")).isEmpty());
    }
    @Test void unknownOrUnsupportedVersionsNeverApplyRules()throws Exception {
        file("input.sql","CREATE TABLE t (id INT UNSIGNED);");
        for(String v:Arrays.asList(null,"5.7","8.4","8.0.36-beta","latest",">=8.0")) {
            var r=run(request(Scenario.DATABASE_MIGRATION,"MySQL",v,"PostgreSQL","16","input.sql"));
            assertTrue(rules(r).isEmpty());gap(r,"RULE_MATRIX_UNSUPPORTED_OR_VERSION_UNKNOWN");
        }
        assertTrue(rules(run(request(Scenario.DATABASE_MIGRATION,"MySQL","8.0","PostgreSQL","17","input.sql"))).isEmpty());
    }
    @Test void csharpFeaturesDoNotClaimBusinessEquivalence()throws Exception {
        file("Service.cs","class Service { public decimal Amount; public ulong Id; [JsonPropertyName(\"total\")] public uint Count; async Task Go() { await Send(); } }");
        var r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","12","Java","21","Service.cs"));
        assertEquals("INCOMPATIBLE",finding(r,"CS_DECIMAL").outcome());assertEquals("INCOMPATIBLE",finding(r,"CS_UNSIGNED").outcome());
        assertEquals("UNKNOWN",finding(r,"CS_AWAIT").outcome());assertEquals("UNKNOWN",finding(r,"CS_SERIALIZATION").outcome());gap(r,"CSHARP_LEXICAL_ONLY");
    }
    @Test void csharpStringsCommentsAndEscapedIdentifiersDoNotTriggerRules()throws Exception {
        file("Service.cs","class A { string s=\"decimal uint await\"; string v=@\"ulong \"\"decimal\"\"\"; int @await; int @decimal; /* decimal */ }");
        assertTrue(rules(run(request(Scenario.LANGUAGE_MIGRATION,"CSharp","12.0","Java","21.0.1","Service.cs"))).isEmpty());
    }
    @Test void csharpPreprocessorAndInterpolatedRawStringsStayUnknown()throws Exception {
        for(String source:List.of("#if NEVER\ndecimal x;\n#endif","class A { string s=$\"{decimal.MaxValue}\"; }","class A { string s=\"\"\"decimal\"\"\"; }")) {
            file("A.cs",source);var r=run(request(Scenario.LANGUAGE_MIGRATION,"C#","12","Java","21","A.cs"));assertTrue(rules(r).isEmpty());gap(r,"CSHARP_LEXICAL_MODE_UNSUPPORTED");
        }
    }
    @Test void springBootMatchesOnlyMigratedNamespacesAndDirectPomFacts()throws Exception {
        file("A.java","import javax.servlet.Filter; import javax.xml.parsers.DocumentBuilder; import javax.persistence.Entity; class A {}");
        file("pom.xml","<project><properties><java.version>11</java.version></properties><dependencies><dependency><groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId><version>4.0.1</version></dependency></dependencies></project>");
        var r=run(request(Scenario.DEPENDENCY_UPGRADE,"Spring Boot","2.7.18","Spring Boot","3.0.13","A.java","pom.xml"));
        assertEquals(2,rules(r).stream().filter(f->f.rule().ruleId().equals("BOOT_JAKARTA")).count());
        assertEquals("CONDITIONAL",finding(r,"BOOT_JAVA17").outcome());assertEquals("CONDITIONAL",finding(r,"BOOT_SERVLET_DEPENDENCY").outcome());gap(r,"MAVEN_EFFECTIVE_MODEL_UNRESOLVED");
    }
    @Test void sourceVersionConflictWithholdsEarlierJavaFindings()throws Exception {
        file("A.java","import javax.servlet.Filter; class A {}");
        file("pom.xml","<project><parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.0.13</version></parent></project>");
        var r=run(request(Scenario.DEPENDENCY_UPGRADE,"Spring Boot","2.7.18","Spring Boot","3.0.13","A.java","pom.xml"));
        assertTrue(rules(r).isEmpty());gap(r,"PROFILE_SOURCE_VERSION_CONFLICT");
    }
    @Test void mavenProfilesManagementAndCommentsAreNotDirectDependenciesAndXmlIsSafe()throws Exception {
        file("pom.xml","<project><properties><java.version>${jdk}</java.version></properties><!-- javax.servlet --><dependencyManagement><dependencies><dependency><groupId>javax.servlet</groupId><artifactId>javax.servlet-api</artifactId></dependency></dependencies></dependencyManagement></project>");
        var req=request(Scenario.DEPENDENCY_UPGRADE,"Spring Boot","2.7","Spring Boot","3.0","pom.xml");var r=run(req);
        assertTrue(rules(r).isEmpty());gap(r,"MAVEN_PROPERTY_UNRESOLVED");
        file("pom.xml","<!DOCTYPE project [<!ENTITY external SYSTEM 'file:///not-authorized'>]><project>&external;</project>");
        gap(run(req),"MAVEN_XML_UNSUPPORTED");
    }
    @Test void jdkAndHttpClientUpgradesRequireClasspathAndCoexistenceVerification()throws Exception {
        file("A.java","import javax.xml.bind.JAXBContext; import org.apache.http.client.HttpClient; class A {}");
        var jdk=run(request(Scenario.DEPENDENCY_UPGRADE,"Java","8","Java","21","A.java"));
        assertEquals("CONDITIONAL",finding(jdk,"JDK_JAXB").outcome());
        var http=run(request(Scenario.DEPENDENCY_UPGRADE,"org.apache.httpcomponents:httpclient","4.5.14","org.apache.httpcomponents.client5:httpclient5","5.2.3","A.java"));
        assertEquals("CONDITIONAL",finding(http,"HTTPCLIENT_NAMESPACE").outcome());
    }
    @Test void upgradeJavaCommentsAndMalformedImportsProduceNoFindings()throws Exception {
        var req=request(Scenario.DEPENDENCY_UPGRADE,"Spring Boot","2.7","Spring Boot","3.0","A.java");
        file("A.java","// import javax.servlet.Filter;\nimport javax.xml.parsers.DocumentBuilder; class A { String s=\"javax.persistence.Entity\"; }");
        assertTrue(rules(run(req)).isEmpty());
        file("A.java","import javax.servlet.Filter; class {");var invalid=run(req);assertTrue(rules(invalid).isEmpty());gap(invalid,"JAVA_PARSE_FAILED");
    }
    InvestigationRequest refactor(String before,String after)throws Exception {
        file("before/A.java",before);file("after/A.java",after);
        file("change.refactor.json","{\"schemaVersion\":\"archlens.refactor-plan.v1\",\"pairs\":[{\"before\":\"before/A.java\",\"after\":\"after/A.java\"}]}");
        return request(Scenario.REFACTORING,"Java","21","Java","21","before/A.java","after/A.java","change.refactor.json");
    }
    @Test void refactorDetectsDeletedApiBodyAndStateChangesWithoutInventingCallEdges()throws Exception {
        var r=run(refactor("package demo; public class A { int count=1; public int value(int x){return x+count;} public int old(){return value(1);} }",
                "package demo; public class A { int count=2; public int value(int x){return x*count;} }"));
        assertTrue(rules(r).stream().anyMatch(f->f.rule().ruleId().equals("JAVA_API_CHANGE")&&f.outcome().equals("INCOMPATIBLE")));
        assertEquals("UNKNOWN",finding(r,"JAVA_BODY_CHANGE").outcome());assertEquals("UNKNOWN",finding(r,"JAVA_STATE_CHANGE").outcome());
        assertTrue(rules(r).stream().flatMap(f->f.impacts().stream()).anyMatch(i->i.certainty().equals("CANDIDATE")&&i.changeRequired().equals("UNKNOWN")));
        assertNull(r.columnAnalysis());
    }
    @Test void refactorWhitespaceAndCommentsDoNotChangeMethodBehaviorAst()throws Exception {
        var r=run(refactor("public class A { public int value(int x) { return x; } }","public class A { /*注释*/ public int value(int x) {\n// 保持行为\nreturn x; } }"));
        assertEquals("COMPATIBLE",finding(r,"JAVA_API_CHANGE").outcome());assertEquals(1,rules(r).size());
    }
    @Test void refactorReturnVisibilityAndStaticChangesBreakCheckedDescriptors()throws Exception {
        for(String after:List.of("public long value(int x){return x;}","private int value(int x){return x;}","public static int value(int x){return x;}")) {
            var r=run(refactor("public class A { public int value(int x){return x;} }","public class A { "+after+" }"));
            assertEquals("INCOMPATIBLE",finding(r,"JAVA_API_CHANGE").outcome());
        }
    }
    @Test void refactorPackageMoveAndInheritanceRemainUnknown()throws Exception {
        var moved=run(refactor("package old; public class A { public int f(){return 1;} }","package newer; public class A { public int f(){return 1;} }"));
        assertEquals("UNKNOWN",finding(moved,"JAVA_API_CHANGE").outcome());
        var inherited=run(refactor("public class A extends Parent { public int f(){return 1;} }","public class A extends Parent {}"));
        assertTrue(rules(inherited).isEmpty());gap(inherited,"REFACTOR_CLASS_SHAPE_UNSUPPORTED");
        var object=run(refactor("public class A { public int hashCode(){return 1;} }","public class A {}"));
        assertTrue(rules(object).isEmpty());gap(object,"REFACTOR_INHERITED_OBJECT_METHOD");
    }
    @Test void refactorUnresolvedSignaturesAndUnlistedSourcesAreNotReadOrDeclaredCompatible()throws Exception {
        var req=refactor("public class A { public String f(){return null;} }","public class A {}");var r=run(req);
        assertTrue(rules(r).isEmpty());gap(r,"REFACTOR_SIGNATURE_UNRESOLVED");
        var unlisted=run(request(Scenario.REFACTORING,"Java","21","Java","21","change.refactor.json","before/A.java"));gap(unlisted,"REFACTOR_PAIR_SOURCE_MISSING");assertEquals(2,unlisted.sources().size());
        file("change.refactor.json","{\"schemaVersion\":\"archlens.refactor-plan.v1\",\"pairs\":[{\"before\":\"../secret.java\",\"after\":\"after/A.java\"}]}");gap(run(req),"REFACTOR_PLAN_INVALID");
    }
    @Test void driftAndCancellationWithholdScenarioFindings()throws Exception {
        file("input.sql","CREATE TABLE t (id INT UNSIGNED);");var req=request(Scenario.DATABASE_MIGRATION,"MySQL","8.0","PostgreSQL","16","input.sql");
        var r=new InvestigationEngine().investigate(root,req,()->false,sources->{try{file("input.sql","SELECT 1;");}catch(Exception e){throw new RuntimeException(e);}});
        assertTrue(rules(r).isEmpty());gap(r,"SOURCE_DRIFT");gap(r,"RULE_RESULTS_WITHHELD");
        var cancelled=new InvestigationEngine().investigate(root,req,()->true);assertEquals(InvestigationReport.Status.CANCELLED,cancelled.status());assertTrue(rules(cancelled).isEmpty());
    }
    @Test void sourceAndRuleFingerprintsAreStableAndEveryFindingHasResolvableEvidence()throws Exception {
        var first=db("MySQL","8.0","CREATE TABLE t (id INT UNSIGNED);");var second=db("MySQL","8.0","CREATE TABLE t (id INT UNSIGNED);");
        assertEquals(first.inputFingerprint(),second.inputFingerprint());assertEquals(rules(first),rules(second));
        for(var f:rules(first)) {
            assertEquals(f.evidenceIds(),f.evidence().stream().map(InvestigationReport.Evidence::evidenceId).toList());
            assertTrue(f.evidence().stream().allMatch(e->first.sources().stream().anyMatch(s->s.sourceId().equals(e.sourceId())&&s.sha256().equals(e.sourceHash()))));
            assertFalse(f.rule().officialSources().isEmpty());assertFalse(f.recommendations().isEmpty());
        }
        assertNotEquals(first.inputFingerprint(),db("MySQL","8.0","CREATE TABLE t (id INT);").inputFingerprint());
    }
    @Test void excessiveFindingsAreBoundedAndNeverTurnIntoAFalseCleanReport()throws Exception {
        var r=db("MySQL","8.0","CREATE TABLE t (id INT UNSIGNED);\n".repeat(510));
        assertTrue(rules(r).isEmpty());gap(r,"RULE_FINDING_LIMIT");assertEquals("UNKNOWN",r.findings().getFirst().outcome());
    }
    @Test void historicalFindingJsonRemainsReadable()throws Exception {
        String json="{\"findingId\":\"old\",\"outcome\":\"UNKNOWN\",\"ruleRef\":\"old\",\"evidenceIds\":[],\"conditions\":[],\"unknownReasons\":[\"no rules\"]}";
        var finding=Json.MAPPER.readValue(json,InvestigationReport.Finding.class);assertNull(finding.rule());assertTrue(finding.evidence().isEmpty());
    }
    @Test void javaAstEvidenceUsesOriginalUnicodeAndCrLfPositions()throws Exception {
        String source="// 中文😀\r\n\timport javax.servlet.Filter;\r\nclass A {}";file("A.java",source);
        var r=run(request(Scenario.DEPENDENCY_UPGRADE,"Spring Boot","2.7","Spring Boot","3.0","A.java"));
        var ev=finding(r,"BOOT_JAKARTA").evidence().getFirst();byte[] bytes=source.getBytes(StandardCharsets.UTF_8);
        assertEquals("import javax.servlet.Filter;",new String(Arrays.copyOfRange(bytes,ev.location().byteOffset().intValue(),(int)(ev.location().byteOffset()+ev.location().byteLength())),StandardCharsets.UTF_8));
        assertEquals(2,ev.location().startLine());assertEquals(2,ev.location().startColumn());
    }
    @Test void cancellationPollingDoesNotBecomeOneDatabaseRoundTripPerToken()throws Exception {
        // 模拟存储模式的取消回调；数千 token 不应触发数千次远端状态查询。
        file("input.sql","SELECT "+"x,".repeat(2000)+"x FROM t;");
        var polls=new java.util.concurrent.atomic.AtomicInteger();
        var req=request(Scenario.DATABASE_MIGRATION,"MySQL","8.0","PostgreSQL","16","input.sql");
        var r=new InvestigationEngine().investigate(root,req,()->{polls.incrementAndGet();return false;});
        gap(r,"SQL_SELECT_FEATURE_SCAN_ONLY");assertTrue(polls.get()<500,"Cancellation polling should be time-throttled");
    }
}
