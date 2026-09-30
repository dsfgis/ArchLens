package io.archlens.investigation.database;

import java.util.function.BooleanSupplier;

public final class DatabaseCollectors {
    private DatabaseCollectors(){}
    public static DatabaseInventory collect(BusinessContext.Source source,BusinessConnection connection,BooleanSupplier cancelled,long deadline,boolean testOnly){
        return "MySQL".equals(source.product())?new MysqlCollector().collect(source,connection,cancelled,deadline,testOnly):
                new JdbcMetadataCollector().collect(source,connection,cancelled,deadline,testOnly);
    }
}
