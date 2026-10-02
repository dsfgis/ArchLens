package io.archlens.storage;

import io.archlens.contract.Json;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import static io.archlens.contract.ContractException.require;

/** Installs additive Migration Agent schemas inside the V001 initializer's advisory-locked transaction. */
final class PgMigrationSchema {
    private PgMigrationSchema() {}

    static void install(Connection connection, Statement statement) throws IOException, SQLException {
        installVersion(connection, statement, 2, "/db/V002__migration_runtime.sql");
        installVersion(connection, statement, 3, "/db/V003__migration_submission.sql");
        installVersion(connection, statement, 4, "/db/V004__migration_tool_result.sql");
    }

    private static void installVersion(Connection connection, Statement statement, int version, String resourceName)
            throws IOException, SQLException {
        byte[] bytes;
        try (var resource = PgMigrationSchema.class.getResourceAsStream(resourceName)) {
            require(resource != null, "MIGRATION_MISSING", "Migration runtime schema is missing");
            bytes = resource.readAllBytes();
        }
        String checksum = Json.sha256(bytes);
        try (var query = connection.prepareStatement("SELECT checksum FROM archlens.schema_version WHERE version=?")) {
            query.setInt(1, version);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    require(checksum.equals(rows.getString(1)), "MIGRATION_DRIFT", "Installed migration differs from this build");
                    return;
                }
            }
        }
        // These migrations use plain DDL and full-line comments. Strip comments before splitting statements.
        StringBuilder commands = new StringBuilder();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R", -1)) {
            if (!line.stripLeading().startsWith("--")) commands.append(line).append('\n');
        }
        for (String command : commands.toString().split(";")) {
            if (!command.isBlank()) statement.execute(command);
        }
        try (var insert = connection.prepareStatement("INSERT INTO archlens.schema_version(version,checksum) VALUES(?,?)")) {
            insert.setInt(1, version);
            insert.setString(2, checksum);
            insert.executeUpdate();
        }
    }
}
