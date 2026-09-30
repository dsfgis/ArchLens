package io.archlens.investigation.database;

import io.archlens.contract.Json;
import java.sql.*;
import java.util.*;
import static io.archlens.contract.ContractException.require;

/** 仅在本次本机请求中传递。database 是对象模式，service 是 Oracle 服务名或 KingbaseES 数据库名。 */
public record BusinessConnection(String host,int port,String database,String username,String password,String tlsMode,
                                 String product,String service) {
    public BusinessConnection(String host,int port,String database,String username,String password,String tlsMode) {
        this(host,port,database,username,password,tlsMode,"MySQL",null);
    }
    public BusinessConnection {
        product=product==null?"MySQL":product;
        require(host!=null&&host.length()<=253&&host.matches("[A-Za-z0-9.-]+")&&!host.startsWith(".")&&!host.endsWith("."),"DATASOURCE_CONFIG_INVALID","Use a hostname or IPv4 address without URL options");
        require(port>0&&port<=65535,"DATASOURCE_CONFIG_INVALID","Invalid port");
        new BusinessContext.Source(product,database,null,null);
        require(username!=null&&!username.isBlank()&&username.length()<=128&&password!=null&&password.length()<=4096,"DATASOURCE_CONFIG_INVALID","Invalid credentials");
        require(("MySQL".equals(product)&&Set.of("VERIFY_IDENTITY","REQUIRED","DISABLED").contains(tlsMode))||
                (!"MySQL".equals(product)&&"DRIVER_DEFAULT".equals(tlsMode)),"DATASOURCE_CONFIG_INVALID","Unsupported TLS mode for database product");
        require(("MySQL".equals(product)&&service==null)||
                ("DM".equals(product)&&service==null)||
                (Set.of("Oracle","KingbaseES").contains(product)&&validName(service)),
                "DATASOURCE_CONFIG_INVALID","Invalid database service or catalog");
    }
    private static boolean validName(String value){return value!=null&&value.matches("[\\p{L}\\p{N}_$.-]{1,128}")&&!value.startsWith(".")&&!value.endsWith(".");}
    public String fingerprint(){
        if("MySQL".equals(product))return Json.hashParts("mysql-source-v1",host.toLowerCase(Locale.ROOT),port,database,username,tlsMode);
        return Json.hashParts("jdbc-source-v1",product,host.toLowerCase(Locale.ROOT),port,service,database,username,tlsMode);
    }
    public void verify(BusinessContext.Source source) {
        require(source!=null&&product.equals(source.product())&&database.equals(source.database())&&
                (source.connectionFingerprint()==null||fingerprint().equals(source.connectionFingerprint())),
                "DATASOURCE_IDENTITY_CHANGED","Use the original database endpoint and account");
    }
    public Connection connect() throws SQLException {
        Properties p=new Properties();p.setProperty("user",username);p.setProperty("password",password);
        String url;
        switch(product){
            case "MySQL" -> {
                p.setProperty("connectTimeout","5000");p.setProperty("socketTimeout","5000");p.setProperty("sslMode",tlsMode);
                p.setProperty("allowMultiQueries","false");p.setProperty("allowLoadLocalInfile","false");p.setProperty("allowUrlInLocalInfile","false");
                p.setProperty("autoDeserialize","false");p.setProperty("allowPublicKeyRetrieval","false");p.setProperty("readOnlyPropagatesToServer","true");
                url="jdbc:mysql://"+host+":"+port+"/"+database;
            }
            case "Oracle" -> {p.setProperty("oracle.net.CONNECT_TIMEOUT","5000");p.setProperty("oracle.jdbc.ReadTimeout","5000");url="jdbc:oracle:thin:@//"+host+":"+port+"/"+service;}
            case "KingbaseES" -> {p.setProperty("connectTimeout","5");p.setProperty("socketTimeout","5");url="jdbc:kingbase8://"+host+":"+port+"/"+service;}
            case "DM" -> {p.setProperty("loginTimeout","5000");url="jdbc:dm://"+host+":"+port;}
            default -> throw new SQLException("Unsupported database product");
        }
        if(!"MySQL".equals(product)){
            String driver=switch(product){case "Oracle"->"oracle.jdbc.OracleDriver";case "KingbaseES"->"com.kingbase8.Driver";case "DM"->"dm.jdbc.driver.DmDriver";default->throw new SQLException("Unsupported database product");};
            try{Class.forName(driver);}catch(ClassNotFoundException|LinkageError e){throw new SQLException("Database JDBC driver unavailable","IM003",e);}
        }
        try {return DriverManager.getConnection(url,p);}
        catch(SQLException e){if(e.getMessage()!=null&&e.getMessage().startsWith("No suitable driver"))throw new SQLException("Database JDBC driver unavailable","IM003",e);throw e;}
    }
    @Override public String toString(){return "BusinessConnection[REDACTED]";}
}
