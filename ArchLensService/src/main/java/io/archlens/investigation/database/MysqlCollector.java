package io.archlens.investigation.database;

import io.archlens.contract.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static io.archlens.contract.ContractException.require;
import static io.archlens.investigation.database.DatabaseInventory.*;

/** 所有查询为固定的系统元数据 SELECT；范围由绑定参数限定，不提供任意 SQL 或业务行读取入口。 */
public final class MysqlCollector {
    @FunctionalInterface public interface Opener {Connection open(BusinessConnection config)throws SQLException;}
    private final Opener opener;
    public MysqlCollector(){this(BusinessConnection::connect);}
    public MysqlCollector(Opener opener){this.opener=opener;}
    public DatabaseInventory collect(BusinessContext.Source source,BusinessConnection config,BooleanSupplier cancelled,long deadline,boolean testOnly) {
        String start=Instant.now().toString();
        if(config==null)return failure(source,start,"DATASOURCE_CREDENTIALS_REQUIRED");
        config.verify(source);
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        var future=executor.submit(()->new Scan(source,config,cancelled,deadline,start).run(testOnly));
        try {
            while(true){active(cancelled,deadline);try{return future.get(Math.min(100,Math.max(1,(deadline-System.nanoTime())/1_000_000)),TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}}
        }catch(ExecutionException e){return failure(source,start,code(e.getCause()));}
        catch(InterruptedException e){Thread.currentThread().interrupt();return failure(source,start,"DB_CANCELLED");}
        catch(ContractException e){return failure(source,start,e.code());}
        finally{future.cancel(true);executor.shutdownNow();}
    }
    private static DatabaseInventory failure(BusinessContext.Source source,String start,String code){return new DatabaseInventory(VERSION,"MySQL",null,source.database(),source.connectionFingerprint(),start,Instant.now().toString(),"UNAVAILABLE",null,List.of(),Map.of(),List.of(code));}
    static String code(Throwable e){
        if(e instanceof ContractException c)return c.code();
        if(e instanceof SQLTimeoutException)return "DB_TIME_BUDGET";
        if(e instanceof SQLException s){String state=s.getSQLState();if("28000".equals(state))return "DB_AUTH_FAILED";if(s.getErrorCode()==1049)return "DB_DATABASE_UNAVAILABLE";if(state!=null&&state.startsWith("42"))return "DB_METADATA_PERMISSION_OR_QUERY_FAILED";return "DB_CONNECTION_OR_QUERY_FAILED";}
        return "DB_COLLECTION_FAILED";
    }
    static void active(BooleanSupplier cancelled,long deadline){
        if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new ContractException("DB_CANCELLED","Database collection cancelled");
        if(System.nanoTime()>=deadline)throw new ContractException("DB_TIME_BUDGET","Database budget exhausted");
    }
    private final class Scan {
        final BusinessContext.Source source;final BusinessConnection config;final BooleanSupplier cancel;final long deadline;final String start;
        final List<String> gaps=new ArrayList<>();final Map<String,MutableTable> tables=new LinkedHashMap<>();final Map<String,String> settings=new TreeMap<>();
        final List<ColumnDetail> columnDetails=new ArrayList<>();final List<TableDetail> tableDetails=new ArrayList<>();
        final List<ViewDetail> viewDetails=new ArrayList<>();final List<CheckDetail> checkDetails=new ArrayList<>();
        final List<ProgramObject> programs=new ArrayList<>();final List<ProgramParameter> parameters=new ArrayList<>();
        String version;int characters;long lastPoll;boolean polled;
        Scan(BusinessContext.Source s,BusinessConnection c,BooleanSupplier b,long d,String start){source=s;config=c;cancel=b;deadline=d;this.start=start;}
        void check(){long n=System.nanoTime();active(()->{if(!polled||n-lastPoll>100_000_000){polled=true;lastPoll=n;return cancel.getAsBoolean();}return false;},deadline);}
        String str(ResultSet r,String key)throws SQLException {String v=r.getString(key);if(v!=null){characters+=v.length();require(v.length()<=4096&&characters<=500000,"DB_RESULT_LIMIT","Metadata text budget exhausted");}return v;}
        interface Row {void accept(ResultSet row)throws SQLException;}
        void query(Connection c,String sql,String scope,int limit,Row row,String section)throws SQLException {
            check();try(var p=c.prepareStatement(sql)) {
                p.setQueryTimeout((int)Math.max(1,Math.min(5,(deadline-System.nanoTime())/1_000_000_000)));p.setMaxRows(limit+1);
                if(scope!=null)p.setString(1,scope);
                try(var r=p.executeQuery()){int n=0;while(r.next()){check();if(n++>=limit){gaps.add("DB_"+section+"_LIMIT");break;}row.accept(r);}}
            }
        }
        void section(Connection c,String sql,int limit,Row row,String name){
            try{query(c,sql,source.database(),limit,row,name);}
            catch(SQLException e){gaps.add(name+":"+code(e));}
        }
        DatabaseInventory run(boolean testOnly)throws SQLException {
            check();try(var c=opener.open(config)) {
                check();c.setReadOnly(true);c.setAutoCommit(false);
                try {
                    query(c,"SELECT VERSION() AS v, @@version_comment AS vendor, @@session.transaction_read_only AS ro, @@sql_mode AS mode, @@lower_case_table_names AS names, @@character_set_server AS charset, @@collation_server AS collation",null,1,r->{
                        version=str(r,"v");String vendor=str(r,"vendor");
                        require(version!=null&&version.startsWith("8.")&&!version.toLowerCase(Locale.ROOT).contains("mariadb")&&(vendor==null||!vendor.toLowerCase(Locale.ROOT).contains("mariadb")),"DB_VERSION_UNSUPPORTED","Collector currently supports MySQL 8.x");
                        require(r.getInt("ro")==1,"DB_READ_ONLY_NOT_ENFORCED","Read-only session was not established");
                        for(String key:List.of("mode","names","charset","collation")){String value=str(r,key);if(value!=null)settings.put(key,value);}
                    },"SERVER");
                    final boolean[] visible={false};query(c,"SELECT SCHEMA_NAME, DEFAULT_CHARACTER_SET_NAME, DEFAULT_COLLATION_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = ?",source.database(),1,r->{visible[0]=true;settings.put("databaseCharset",str(r,"DEFAULT_CHARACTER_SET_NAME"));settings.put("databaseCollation",str(r,"DEFAULT_COLLATION_NAME"));},"SCHEMA");
                    require(visible[0],"DB_SCOPE_NOT_VISIBLE","Database is unavailable to this account");
                    if(source.declaredVersion()!=null&&!version.equals(source.declaredVersion())&&!version.startsWith(source.declaredVersion()+"."))gaps.add("DB_DECLARED_VERSION_CONFLICT");
                    if(!testOnly) {
                        section(c,"SELECT TABLE_NAME, TABLE_TYPE, ENGINE, TABLE_COLLATION, CHAR_LENGTH(TABLE_COMMENT) AS comment_length FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME",200,r->{String name=str(r,"TABLE_NAME");tables.put(name,new MutableTable(name,str(r,"TABLE_TYPE"),str(r,"ENGINE"),str(r,"TABLE_COLLATION")));tableDetails.add(new TableDetail(name,r.getInt("comment_length")>0));},"TABLES");
                        section(c,"SELECT TABLE_NAME,COLUMN_NAME,ORDINAL_POSITION,DATA_TYPE,COLUMN_TYPE,IS_NULLABLE,EXTRA,CHARACTER_SET_NAME,COLLATION_NAME,COLUMN_DEFAULT IS NOT NULL AS has_default,CHAR_LENGTH(COLUMN_COMMENT) AS comment_length,CHAR_LENGTH(GENERATION_EXPRESSION) AS generation_length FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME,ORDINAL_POSITION",5000,r->{var t=tables.get(str(r,"TABLE_NAME"));if(t!=null)t.columns.add(new Column(str(r,"COLUMN_NAME"),r.getInt("ORDINAL_POSITION"),str(r,"DATA_TYPE"),str(r,"COLUMN_TYPE"),"YES".equals(str(r,"IS_NULLABLE")),str(r,"EXTRA"),str(r,"CHARACTER_SET_NAME"),str(r,"COLLATION_NAME")));
                            columnDetails.add(new ColumnDetail(t.name,str(r,"COLUMN_NAME"),r.getBoolean("has_default"),
                                    t.columns.getLast().extra()!=null&&t.columns.getLast().extra().contains("DEFAULT_GENERATED"),
                                    r.getInt("comment_length")>0,r.getInt("generation_length")>0));},"COLUMNS");
                        section(c,"SELECT TABLE_NAME,INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,INDEX_TYPE FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME,INDEX_NAME,SEQ_IN_INDEX",5000,r->{var t=tables.get(str(r,"TABLE_NAME"));if(t!=null)t.indexes.add(new IndexPart(str(r,"INDEX_NAME"),r.getInt("NON_UNIQUE")==0,r.getInt("SEQ_IN_INDEX"),str(r,"COLUMN_NAME"),str(r,"INDEX_TYPE")));},"INDEXES");
                        section(c,"SELECT k.TABLE_NAME,k.CONSTRAINT_NAME,t.CONSTRAINT_TYPE,k.ORDINAL_POSITION,k.COLUMN_NAME,k.REFERENCED_TABLE_SCHEMA,k.REFERENCED_TABLE_NAME,k.REFERENCED_COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS t ON t.CONSTRAINT_SCHEMA=k.CONSTRAINT_SCHEMA AND t.TABLE_NAME=k.TABLE_NAME AND t.CONSTRAINT_NAME=k.CONSTRAINT_NAME WHERE k.TABLE_SCHEMA = ? ORDER BY k.TABLE_NAME,k.CONSTRAINT_NAME,k.ORDINAL_POSITION",5000,r->{var t=tables.get(str(r,"TABLE_NAME"));if(t!=null)t.keys.add(new KeyPart(str(r,"CONSTRAINT_NAME"),str(r,"CONSTRAINT_TYPE"),r.getInt("ORDINAL_POSITION"),str(r,"COLUMN_NAME"),str(r,"REFERENCED_TABLE_SCHEMA"),str(r,"REFERENCED_TABLE_NAME"),str(r,"REFERENCED_COLUMN_NAME")));},"KEYS");
                        section(c,"SELECT TABLE_NAME,CHAR_LENGTH(VIEW_DEFINITION) AS definition_length,IS_UPDATABLE,CHECK_OPTION,SECURITY_TYPE FROM INFORMATION_SCHEMA.VIEWS WHERE TABLE_SCHEMA = ? ORDER BY TABLE_NAME",200,r->{
                            String name=str(r,"TABLE_NAME");if(tables.containsKey(name)){
                                int length=r.getInt("definition_length");boolean definitionVisible=!r.wasNull();viewDetails.add(new ViewDetail(name,definitionVisible,definitionVisible?length:null,
                                        "YES".equals(str(r,"IS_UPDATABLE")),str(r,"CHECK_OPTION"),str(r,"SECURITY_TYPE")));}},"VIEWS");
                        section(c,"SELECT tc.TABLE_NAME,tc.CONSTRAINT_NAME,tc.ENFORCED,CHAR_LENGTH(cc.CHECK_CLAUSE) AS clause_length FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc JOIN INFORMATION_SCHEMA.CHECK_CONSTRAINTS cc ON cc.CONSTRAINT_SCHEMA=tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME=tc.CONSTRAINT_NAME WHERE tc.TABLE_SCHEMA = ? AND tc.CONSTRAINT_TYPE='CHECK' ORDER BY tc.TABLE_NAME,tc.CONSTRAINT_NAME",5000,r->{
                            String table=str(r,"TABLE_NAME");if(tables.containsKey(table)){
                                int length=r.getInt("clause_length");boolean definitionVisible=!r.wasNull();checkDetails.add(new CheckDetail(table,str(r,"CONSTRAINT_NAME"),definitionVisible,definitionVisible?length:null,"YES".equals(str(r,"ENFORCED"))));}},"CHECKS");
                        section(c,"SELECT ROUTINE_NAME,ROUTINE_TYPE,DTD_IDENTIFIER,SECURITY_TYPE,SQL_DATA_ACCESS,CHAR_LENGTH(ROUTINE_DEFINITION) AS body_length FROM INFORMATION_SCHEMA.ROUTINES WHERE ROUTINE_SCHEMA = ? ORDER BY ROUTINE_NAME",200,r->{
                            int length=r.getInt("body_length");boolean definitionVisible=!r.wasNull();programs.add(new ProgramObject(str(r,"ROUTINE_TYPE"),str(r,"ROUTINE_NAME"),null,str(r,"DTD_IDENTIFIER"),
                                    str(r,"SECURITY_TYPE"),str(r,"SQL_DATA_ACCESS"),null,null,definitionVisible,definitionVisible?length:null));},"ROUTINES");
                        section(c,"SELECT TRIGGER_NAME,EVENT_OBJECT_TABLE,EVENT_MANIPULATION,ACTION_TIMING,CHAR_LENGTH(ACTION_STATEMENT) AS body_length FROM INFORMATION_SCHEMA.TRIGGERS WHERE TRIGGER_SCHEMA = ? ORDER BY TRIGGER_NAME",500,r->{
                            String table=str(r,"EVENT_OBJECT_TABLE");if(tables.containsKey(table)){
                                int length=r.getInt("body_length");boolean definitionVisible=!r.wasNull();programs.add(new ProgramObject("TRIGGER",str(r,"TRIGGER_NAME"),table,null,null,null,
                                        str(r,"EVENT_MANIPULATION"),str(r,"ACTION_TIMING"),definitionVisible,definitionVisible?length:null));}},"TRIGGERS");
                        section(c,"SELECT SPECIFIC_NAME,ORDINAL_POSITION,PARAMETER_MODE,PARAMETER_NAME,DTD_IDENTIFIER FROM INFORMATION_SCHEMA.PARAMETERS WHERE SPECIFIC_SCHEMA = ? ORDER BY SPECIFIC_NAME,ORDINAL_POSITION",1000,r->{
                            parameters.add(new ProgramParameter(str(r,"SPECIFIC_NAME"),r.getInt("ORDINAL_POSITION"),str(r,"PARAMETER_MODE"),
                                    str(r,"PARAMETER_NAME"),str(r,"DTD_IDENTIFIER")));},"PARAMETERS");
                    }
                    check();
                }finally{c.rollback();}
            }
            var result=tables.values().stream().map(t->t.freeze(source,config.fingerprint())).toList();
            gaps.addAll(List.of("DB_ACCOUNT_VISIBLE_OBJECTS_ONLY","DB_NON_ATOMIC_METADATA","DB_CODE_BINDING_UNAVAILABLE",
                    "DB_DEFINITION_AND_DEFAULT_TEXT_WITHHELD"));
            if(testOnly)gaps.add("DB_CONNECTION_TEST_ONLY");
            ExtendedMetadata details=testOnly?null:new ExtendedMetadata(columnDetails,tableDetails,viewDetails,checkDetails,programs,parameters);
            return new DatabaseInventory(VERSION,"MySQL",version,source.database(),config.fingerprint(),start,Instant.now().toString(),
                    testOnly?"CONNECTED":"PARTIAL",DatabaseInventory.hash(result,settings,details),result,settings,gaps,details);
        }
    }
    private static final class MutableTable {
        final String name,kind,engine,collation;final List<Column> columns=new ArrayList<>();final List<IndexPart> indexes=new ArrayList<>();final List<KeyPart> keys=new ArrayList<>();
        MutableTable(String n,String k,String e,String c){name=n;kind=k;engine=e;collation=c;}
        Table freeze(BusinessContext.Source s,String fingerprint){return new Table(Json.hashParts(fingerprint,s.database(),name,kind,columns,indexes,keys),name,kind,engine,collation,columns,indexes,keys);}
    }
}
