package io.archlens.investigation.database;

import io.archlens.contract.*;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static io.archlens.investigation.database.DatabaseInventory.*;

/** Oracle、KingbaseES、DM 的有界 JDBC 元数据观察；不接受 SQL、URL 或业务行查询。 */
public final class JdbcMetadataCollector {
    @FunctionalInterface public interface Opener {Connection open(BusinessConnection config)throws SQLException;}
    private final Opener opener;
    public JdbcMetadataCollector(){this(BusinessConnection::connect);}
    public JdbcMetadataCollector(Opener opener){this.opener=opener;}
    public DatabaseInventory collect(BusinessContext.Source source,BusinessConnection config,BooleanSupplier cancelled,long deadline,boolean testOnly){
        String start=Instant.now().toString();
        if(config==null)return fail(source,start,"DATASOURCE_CREDENTIALS_REQUIRED");
        config.verify(source);
        var executor=Executors.newVirtualThreadPerTaskExecutor();
        var future=executor.submit(()->scan(source,config,cancelled,deadline,start,testOnly));
        try {while(true){MysqlCollector.active(cancelled,deadline);try{return future.get(Math.min(100,Math.max(1,(deadline-System.nanoTime())/1_000_000)),TimeUnit.MILLISECONDS);}catch(TimeoutException ignored){}}}
        catch(ExecutionException e){return fail(source,start,code(e.getCause()));}
        catch(InterruptedException e){Thread.currentThread().interrupt();return fail(source,start,"DB_CANCELLED");}
        catch(ContractException e){return fail(source,start,e.code());}
        finally{future.cancel(true);executor.shutdownNow();}
    }
    private static DatabaseInventory fail(BusinessContext.Source source,String start,String code){return new DatabaseInventory(VERSION,source.product(),null,source.database(),source.connectionFingerprint(),start,Instant.now().toString(),"UNAVAILABLE",null,List.of(),Map.of(),List.of(code));}
    private static String code(Throwable error){
        if(error instanceof ContractException c)return c.code();
        if(error instanceof SQLTimeoutException)return "DB_TIME_BUDGET";
        if(error instanceof SQLException s){String state=s.getSQLState();if("IM003".equals(state))return "DB_DRIVER_UNAVAILABLE";if(state!=null&&state.startsWith("28"))return "DB_AUTH_FAILED";if(state!=null&&state.startsWith("42"))return "DB_METADATA_PERMISSION_OR_QUERY_FAILED";return "DB_CONNECTION_OR_QUERY_FAILED";}
        return "DB_COLLECTION_FAILED";
    }
    private static void check(BooleanSupplier cancelled,long deadline){MysqlCollector.active(cancelled,deadline);}
    private static String safe(String s){return s==null?null:s.length()<=4096?s:null;}
    private static String value(ResultSet r,String key)throws SQLException{return safe(r.getString(key));}
    private static String pattern(DatabaseMetaData m,String name)throws SQLException {String escape=m.getSearchStringEscape();if(escape==null||escape.isEmpty())return name;return name.replace(escape,escape+escape).replace("%",escape+"%").replace("_",escape+"_");}
    private static DatabaseInventory scan(BusinessContext.Source source,BusinessConnection config,BooleanSupplier cancelled,long deadline,String start,boolean testOnly)throws SQLException {
        List<String> gaps=new ArrayList<>();Map<String,String> settings=new TreeMap<>();List<Table> tables=new ArrayList<>();List<ProgramObject> programs=new ArrayList<>();
        String version;
        try(Connection c=config.connect()){
            check(cancelled,deadline);c.setReadOnly(true);c.setAutoCommit(false);try{c.setNetworkTimeout(Runnable::run,Math.max(1000,(int)Math.min(5000,(deadline-System.nanoTime())/1_000_000)));}
            catch(SQLFeatureNotSupportedException e){gaps.add("DB_NETWORK_TIMEOUT_UNSUPPORTED");}
            try {
                DatabaseMetaData m=c.getMetaData();version=safe(m.getDatabaseProductVersion());
                String actual=Objects.toString(m.getDatabaseProductName(),"").toLowerCase(Locale.ROOT);
                boolean expected=switch(source.product()) {case "Oracle"->actual.contains("oracle");case "KingbaseES"->actual.contains("kingbase");case "DM"->actual.contains("dm")||actual.contains("dameng");default->false;};
                if(!expected)throw new ContractException("DB_PRODUCT_MISMATCH","Connected server is not the declared database product");
                if(source.declaredVersion()!=null&&version!=null&&!version.equals(source.declaredVersion())&&!version.startsWith(source.declaredVersion()+"."))gaps.add("DB_DECLARED_VERSION_CONFLICT");
                String catalog="KingbaseES".equals(source.product())?config.service():null;
                String scope=source.database(),scopePattern=pattern(m,scope);
                boolean visible=false;int seen=0;
                try(ResultSet schemas=m.getSchemas(catalog,scopePattern)){
                    while(schemas.next()){check(cancelled,deadline);if(++seen>1000){gaps.add("DB_SCHEMAS_LIMIT");break;}if(scope.equals(value(schemas,"TABLE_SCHEM")))visible=true;}
                }catch(SQLFeatureNotSupportedException e){gaps.add("DB_SCHEMA_LIST_UNSUPPORTED");visible=true;}
                if(!visible)throw new ContractException("DB_SCOPE_NOT_VISIBLE","Selected schema is not visible to this account");
                settings.put("metadataApi","JDBC DatabaseMetaData");
                if(!testOnly){
                    try(ResultSet rs=m.getTables(catalog,scopePattern,"%",new String[]{"TABLE","VIEW","MATERIALIZED VIEW","SYNONYM"})){
                        int n=0,columnTotal=0,indexTotal=0,keyTotal=0;while(rs.next()){check(cancelled,deadline);if(!scope.equals(value(rs,"TABLE_SCHEM")))continue;
                            if(n++>=200){gaps.add("DB_TABLES_LIMIT");break;}
                            String name=value(rs,"TABLE_NAME"),kind=value(rs,"TABLE_TYPE");if(name==null)continue;
                            List<Column> columns=new ArrayList<>();List<IndexPart> indexes=new ArrayList<>();List<KeyPart> keys=new ArrayList<>();
                            try(ResultSet cr=m.getColumns(catalog,scopePattern,pattern(m,name),"%")){int count=0;while(cr.next()){check(cancelled,deadline);if(count++>=5000||columnTotal++>=5000){gaps.add("DB_COLUMNS_LIMIT");break;}
                                if(!scope.equals(value(cr,"TABLE_SCHEM"))||!name.equals(value(cr,"TABLE_NAME")))continue;
                                columns.add(new Column(value(cr,"COLUMN_NAME"),cr.getInt("ORDINAL_POSITION"),value(cr,"TYPE_NAME"),value(cr,"TYPE_NAME"),cr.getInt("NULLABLE")!=DatabaseMetaData.columnNoNulls,null,null,null));}}
                            catch(SQLException e){gaps.add("DB_COLUMNS:"+code(e));}
                            try(ResultSet ir=m.getIndexInfo(catalog,scope,name,false,false)){int count=0;while(ir.next()){check(cancelled,deadline);if(count++>=5000||indexTotal++>=5000){gaps.add("DB_INDEXES_LIMIT");break;}
                                String index=value(ir,"INDEX_NAME");if(index!=null)indexes.add(new IndexPart(index,!ir.getBoolean("NON_UNIQUE"),ir.getInt("ORDINAL_POSITION"),value(ir,"COLUMN_NAME"),Short.toString(ir.getShort("TYPE"))));}}
                            catch(SQLException e){gaps.add("DB_INDEXES:"+code(e));}
                            try(ResultSet pr=m.getPrimaryKeys(catalog,scope,name)){int count=0;while(pr.next()){check(cancelled,deadline);if(count++>=5000||keyTotal++>=5000){gaps.add("DB_KEYS_LIMIT");break;}
                                keys.add(new KeyPart(value(pr,"PK_NAME"),"PRIMARY KEY",pr.getInt("KEY_SEQ"),value(pr,"COLUMN_NAME"),null,null,null));}}
                            catch(SQLException e){gaps.add("DB_PRIMARY_KEYS:"+code(e));}
                            try(ResultSet fr=m.getImportedKeys(catalog,scope,name)){int count=0;while(fr.next()){check(cancelled,deadline);if(count++>=5000||keyTotal++>=5000){gaps.add("DB_KEYS_LIMIT");break;}
                                keys.add(new KeyPart(value(fr,"FK_NAME"),"FOREIGN KEY",fr.getInt("KEY_SEQ"),value(fr,"FKCOLUMN_NAME"),value(fr,"PKTABLE_SCHEM"),value(fr,"PKTABLE_NAME"),value(fr,"PKCOLUMN_NAME")));}}
                            catch(SQLException e){gaps.add("DB_FOREIGN_KEYS:"+code(e));}
                            tables.add(new Table(Json.hashParts(config.fingerprint(),scope,name,kind,columns,indexes,keys),name,kind,null,null,columns,indexes,keys));
                        }
                    }
                    try(ResultSet pr=m.getProcedures(catalog,scopePattern,"%")){int count=0;while(pr.next()){check(cancelled,deadline);if(!scope.equals(value(pr,"PROCEDURE_SCHEM")))continue;if(count++>=200){gaps.add("DB_PROGRAMS_LIMIT");break;}
                        programs.add(new ProgramObject("PROCEDURE",value(pr,"PROCEDURE_NAME"),null,null,null,null,null,null,false,null));}}
                    catch(SQLException e){gaps.add("DB_PROCEDURES:"+code(e));}
                    try(ResultSet fr=m.getFunctions(catalog,scopePattern,"%")){int count=0;while(fr.next()){check(cancelled,deadline);if(!scope.equals(value(fr,"FUNCTION_SCHEM")))continue;if(count++>=200){gaps.add("DB_PROGRAMS_LIMIT");break;}
                        programs.add(new ProgramObject("FUNCTION",value(fr,"FUNCTION_NAME"),null,null,null,null,null,null,false,null));}}
                    catch(SQLException e){gaps.add("DB_FUNCTIONS:"+code(e));}
                }
            }finally{c.rollback();}
        }
        gaps.addAll(List.of("DB_ACCOUNT_VISIBLE_OBJECTS_ONLY","DB_NON_ATOMIC_METADATA","DB_READ_ONLY_SERVER_NOT_VERIFIED","DB_TLS_POLICY_UNVERIFIED","DB_DEFINITION_AND_DEFAULT_TEXT_WITHHELD"));
        if(testOnly)gaps.add("DB_CONNECTION_TEST_ONLY");
        ExtendedMetadata details=testOnly?null:new ExtendedMetadata(List.of(),List.of(),List.of(),List.of(),programs,List.of());
        return new DatabaseInventory(VERSION,source.product(),version,source.database(),config.fingerprint(),start,Instant.now().toString(),testOnly?"CONNECTED":"PARTIAL",
                DatabaseInventory.hash(tables,settings,details),tables,settings,gaps,details);
    }
}
