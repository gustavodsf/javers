package org.javers.repository.sql.finders;

import org.javers.common.string.ToStringBuilder;
import org.javers.core.json.CdoSnapshotSerialized;
import org.javers.repository.api.QueryParams;
import org.javers.repository.sql.schema.TableNameProvider;
import org.javers.repository.sql.session.Parameter;
import org.javers.repository.sql.session.Session;
import org.polyjdbc.core.type.Timestamp;

import java.util.List;
import java.util.stream.Collectors;

import static org.javers.core.json.typeadapter.util.UtilTypeCoreAdapters.toUtilDate;
import static org.javers.repository.sql.schema.FixedSchemaFactory.*;
import static org.javers.repository.sql.schema.FixedSchemaFactory.GLOBAL_ID_TYPE_NAME;
import static org.javers.repository.sql.session.Parameter.*;

class SnapshotQuery {
    private final QueryParams queryParams;
    private final Session.SelectBuilder selectBuilder;

    public SnapshotQuery(TableNameProvider tableNames, QueryParams queryParams, Session session) {
        this.selectBuilder = session
            .select(
                SNAPSHOT_STATE + ", " +
                SNAPSHOT_TYPE + ", " +
                SNAPSHOT_VERSION + ", " +
                SNAPSHOT_CHANGED + ", " +
                SNAPSHOT_MANAGED_TYPE + ", " +
                COMMIT_PK + ", " +
                COMMIT_AUTHOR + ", " +
                COMMIT_COMMIT_DATE + ", " +
                COMMIT_COMMIT_ID + ", " +
                "g." + GLOBAL_ID_LOCAL_ID + ", " +
                "g." + GLOBAL_ID_FRAGMENT + ", " +
                "g." + GLOBAL_ID_OWNER_ID_FK + ", " +
                "o." + GLOBAL_ID_LOCAL_ID + " owner_" + GLOBAL_ID_LOCAL_ID + ", " +
                "o." + GLOBAL_ID_FRAGMENT + " owner_" + GLOBAL_ID_FRAGMENT + ", " +
                "o." + GLOBAL_ID_TYPE_NAME + " owner_" + GLOBAL_ID_TYPE_NAME
            )
            .from(
                tableNames.getSnapshotTableNameWithSchema() +
                " INNER JOIN " + tableNames.getCommitTableNameWithSchema() + " ON " + COMMIT_PK + " = " + SNAPSHOT_COMMIT_FK +
                " INNER JOIN " + tableNames.getGlobalIdTableNameWithSchema() + " g ON g." + GLOBAL_ID_PK + " = " + SNAPSHOT_GLOBAL_ID_FK +
                " LEFT OUTER JOIN " + tableNames.getGlobalIdTableNameWithSchema() + " o ON o." + GLOBAL_ID_PK + " = g." + GLOBAL_ID_OWNER_ID_FK)
            .queryName("select snapshots");

        this.queryParams = queryParams;
        applyQueryParams();
    }

    private void applyQueryParams() {
        queryParams.changedProperty().ifPresent(changedProperty -> {
            selectBuilder.and(SNAPSHOT_CHANGED, "like", stringParam("%\"" + queryParams.changedProperty().get() +"\"%"));
        });

        queryParams.from().ifPresent(from -> {
            selectBuilder.and(COMMIT_COMMIT_DATE, ">=", localDateTimeParam(from));
        });

        queryParams.to().ifPresent(to -> {
            selectBuilder.and(COMMIT_COMMIT_DATE, "<=", localDateTimeParam(to));
        });

        queryParams.toCommitId().ifPresent(commitId -> {
            selectBuilder.and(COMMIT_COMMIT_ID, "<=", bigDecimalParam(commitId.valueAsNumber()));
        });

        if (queryParams.commitIds().size() > 0) {
            query.append(" AND " + COMMIT_COMMIT_ID + " IN (" + ToStringBuilder.join(
                    queryParams.commitIds().stream().map(c -> c.valueAsNumber()).collect(Collectors.toList())) + ")");
        }
        if (queryParams.version().isPresent()) {
            query.append(" AND " + SNAPSHOT_VERSION + " = :version")
                    .withArgument("version", queryParams.version().get());
        }
        if (queryParams.author().isPresent()) {
            query.append(" AND " + COMMIT_AUTHOR + " = :author")
                    .withArgument("author",  queryParams.author().get());
        }
        if (queryParams.commitProperties().size() > 0) {
            addCommitPropertyConditions(query, queryParams.commitProperties());
        }
        if (queryParams.snapshotType().isPresent()){
            query.append(" AND " + SNAPSHOT_TYPE + " = :snapshotType")
                    .withArgument("snapshotType", queryParams.snapshotType().get().name());
        }
        query.limit(queryParams.limit(), queryParams.skip());
    }


    void addGlobalIdFilter(long globalIdPk) {
        if (!queryParams.isAggregate()) {
            selectBuilder.and("g." + GLOBAL_ID_PK, globalIdPk);
        }
        else {
            selectBuilder.and("( g." + GLOBAL_ID_PK + " = ? OR g." + GLOBAL_ID_OWNER_ID_FK + " = ? )",
                    longParam(globalIdPk), longParam(globalIdPk));
        }
    }

    List<CdoSnapshotSerialized> run() {
        return null;
    }
}
