package it.unibz.inf.ontop.sql;


import it.unibz.inf.ontop.injection.OBDASettings;
import it.unibz.inf.ontop.model.DBMetadata;
import it.unibz.inf.ontop.model.OBDAModel;
import it.unibz.inf.ontop.nativeql.DBConnectionWrapper;
import it.unibz.inf.ontop.nativeql.DBMetadataException;
import it.unibz.inf.ontop.nativeql.DBMetadataExtractor;
import net.sf.jsqlparser.JSQLParserException;

import javax.annotation.Nullable;
import javax.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

import static it.unibz.inf.ontop.mapping.sql.SQLTableNameExtractor.getRealTables;

/**
 * DBMetadataExtractor for JDBC-enabled DBs.
 */
public class SQLDBMetadataExtractor implements DBMetadataExtractor {

    /**
     * If we have to parse the full metadata or just the table list in the mappings.
     */
    private final Boolean obtainFullMetadata;

    /**
     * This represents user-supplied constraints, i.e. primary
     * and foreign keys not present in the database metadata
     *
     * Can be useful for eliminating self-joins
     */
    private final Optional<ImplicitDBConstraintsReader> userConstraints;

    @Inject
    private SQLDBMetadataExtractor(OBDASettings preferences, @Nullable ImplicitDBConstraintsReader userConstraints) {
        this.obtainFullMetadata = preferences.isFullMetadataExtractionEnabled();
        this.userConstraints = Optional.ofNullable(userConstraints);
    }

    /**
     * Expects the DBConnectionWrapper to wrap a JDBC connection.
     */
    @Override
    public RDBMetadata extract(OBDAModel obdaModel, DBConnectionWrapper dbConnection)
            throws DBMetadataException {

        Connection connection = (Connection) dbConnection.getConnection();
        try {
            RDBMetadata metadata = RDBMetadataExtractionTools.createMetadata(connection);
            return extract(obdaModel, dbConnection, metadata);
        } catch (SQLException e) {
            throw new DBMetadataException(e.getMessage());
        }
    }

    @Override
    public RDBMetadata extract(OBDAModel model, @Nullable DBConnectionWrapper dbConnection,
                               DBMetadata partiallyDefinedMetadata) throws DBMetadataException {

        if (!(partiallyDefinedMetadata instanceof RDBMetadata)) {
            throw new IllegalArgumentException("Was expecting a DBMetadata");
        }

        Connection connection = (Connection) dbConnection.getConnection();
        try {
            RDBMetadata metadata = (RDBMetadata) partiallyDefinedMetadata;

            // if we have to parse the full metadata or just the table list in the mappings
            if (obtainFullMetadata) {
                RDBMetadataExtractionTools.loadMetadata(metadata, connection, null);
            }
            else {
                try {
                    // This is the NEW way of obtaining part of the metadata
                    // (the schema.table names) by parsing the mappings

                    // Parse mappings. Just to get the table names in use

                    Set<RelationID> realTables = getRealTables(metadata.getQuotedIDFactory(), model.getMappings());
                    userConstraints.ifPresent(c -> {
                        // Add the tables referred to by user-supplied foreign keys
                        Set<RelationID> referredTables = c.getReferredTables(metadata.getQuotedIDFactory());
                        realTables.addAll(referredTables);
                    });

                    RDBMetadataExtractionTools.loadMetadata(metadata, connection, realTables);
                }
                catch (JSQLParserException e) {
                    System.out.println("Error obtaining the tables" + e);
                }
                catch (SQLException e) {
                    System.out.println("Error obtaining the metadata " + e);
                }
            }

            userConstraints.ifPresent(c ->  {
                c.insertUniqueConstraints(metadata);
                c.insertForeignKeyConstraints(metadata);
            });

            return metadata;

        } catch (SQLException e) {
            throw new DBMetadataException(e.getMessage());
        }    }
}