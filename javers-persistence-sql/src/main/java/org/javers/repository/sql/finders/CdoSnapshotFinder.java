package org.javers.repository.sql.finders;

import java.util.Optional;

import org.javers.common.collections.Lists;
import org.javers.common.collections.Sets;
import org.javers.core.json.CdoSnapshotSerialized;
import org.javers.core.json.JsonConverter;
import org.javers.core.metamodel.object.CdoSnapshot;
import org.javers.core.metamodel.object.GlobalId;
import org.javers.core.metamodel.type.EntityType;
import org.javers.core.metamodel.type.ManagedType;
import org.javers.repository.api.QueryParams;
import org.javers.repository.api.QueryParamsBuilder;
import org.javers.repository.api.SnapshotIdentifier;
import org.javers.repository.sql.repositories.GlobalIdRepository;
import org.javers.repository.sql.schema.TableNameProvider;
import org.javers.repository.sql.session.Session;
import org.polyjdbc.core.PolyJDBC;
import org.polyjdbc.core.query.Order;
import org.polyjdbc.core.query.SelectQuery;

import java.util.*;

import static java.util.stream.Collectors.toList;
import static org.javers.repository.sql.schema.FixedSchemaFactory.*;

public class CdoSnapshotFinder {

    private final PolyJDBC polyJDBC;
    private final GlobalIdRepository globalIdRepository;
    private final CommitPropertyFinder commitPropertyFinder;
    private final CdoSnapshotMapper cdoSnapshotMapper;
    private final CdoSnapshotsEnricher cdoSnapshotsEnricher = new CdoSnapshotsEnricher();
    private JsonConverter jsonConverter;
    private final TableNameProvider tableNameProvider;

    public CdoSnapshotFinder(PolyJDBC polyJDBC, GlobalIdRepository globalIdRepository, CommitPropertyFinder commitPropertyFinder, TableNameProvider tableNameProvider) {
        this.polyJDBC = polyJDBC;
        this.globalIdRepository = globalIdRepository;
        this.commitPropertyFinder = commitPropertyFinder;
        this.cdoSnapshotMapper = new CdoSnapshotMapper();
        this.tableNameProvider = tableNameProvider;
    }

    public Optional<CdoSnapshot> getLatest(GlobalId globalId, Session session, boolean loadCommitProps) {
        Optional<Long> globalIdPk = globalIdRepository.findGlobalIdPk(globalId, session);
        if (!globalIdPk.isPresent()){
            return Optional.empty();
        }

        return selectMaxSnapshotPrimaryKey(globalIdPk.get(), session).map(maxSnapshotId -> {
            QueryParams oneItemLimit = QueryParamsBuilder
                    .withLimit(1)
                    .withCommitProps(loadCommitProps)
                    .build();
            return fetchCdoSnapshots(maxSnapshotId, oneItemLimit, session).get(0);
        });
    }

    public List<CdoSnapshot> getSnapshots(QueryParams queryParams, Session session) {
        return fetchCdoSnapshots(new AnySnapshotFilter(tableNameProvider), Optional.of(queryParams), session);
    }

    public List<CdoSnapshot> getSnapshots(Collection<SnapshotIdentifier> snapshotIdentifiers, Session session) {

        SnapshotFilter snapshotIdentifiersFilter = new SnapshotFilter(tableNameProvider) {
            @Override
            void addWhere(SelectQuery query) {
                query.where("1!=1");
                for (SnapshotIdentifier snapshotIdentifier : snapshotIdentifiers) {
                    Optional<Long> globalIdPk = globalIdRepository.findGlobalIdPk(snapshotIdentifier.getGlobalId(), session);
                    if (globalIdPk.isPresent()) {
                        query.append(" OR (")
                                .append(SNAPSHOT_GLOBAL_ID_FK).append(" = ").append(globalIdPk.get().toString())
                                .append(" AND ")
                                .append(SNAPSHOT_VERSION).append(" = ").append(Long.toString(snapshotIdentifier.getVersion()))
                                .append(")");
                    }
                }
            }
        };

        return fetchCdoSnapshots(snapshotIdentifiersFilter, Optional.empty(), session);
    }

    public List<CdoSnapshot> getStateHistory(Set<ManagedType> managedTypes, QueryParams queryParams, Session session) {
        Set<String> managedTypeNames = Sets.transform(managedTypes, managedType -> managedType.getName());
        ManagedClassFilter classFilter = new ManagedClassFilter(tableNameProvider, managedTypeNames, queryParams.isAggregate());
        return fetchCdoSnapshots(classFilter, Optional.of(queryParams), session);
    }

    public List<CdoSnapshot> getVOStateHistory(EntityType ownerEntity, String fragment, QueryParams queryParams, Session session) {
        VoOwnerEntityFilter voOwnerFilter = new VoOwnerEntityFilter(tableNameProvider, ownerEntity.getName(), fragment);
        return fetchCdoSnapshots(voOwnerFilter, Optional.of(queryParams), session);
    }

    public List<CdoSnapshot> getStateHistory(GlobalId globalId, QueryParams queryParams, Session session) {
        Optional<Long> globalIdPk = globalIdRepository.findGlobalIdPk(globalId, session);

        return globalIdPk.map(id -> fetchCdoSnapshots(id, queryParams, session))
                         .orElse(Collections.emptyList());
    }

    private List<CdoSnapshot> fetchCdoSnapshots(long globalIdPk, QueryParams queryParams, Session session) {
        //TODO!!!!!

        SnapshotQuery query = new SnapshotQuery(tableNameProvider, queryParams, session);
        query.addGlobalIdFilter(globalIdPk);

        List<CdoSnapshotSerialized> serializedSnapshots = query.run();

        if (queryParams.isLoadCommitProps()) {
            List<CommitPropertyDTO> commitPropertyDTOs = commitPropertyFinder.findCommitPropertiesOfSnaphots(
                    serializedSnapshots.stream().map(it -> it.getCommitPk()).collect(toList()));

            cdoSnapshotsEnricher.enrichWithCommitProperties(serializedSnapshots, commitPropertyDTOs);
        }

        return Lists.transform(serializedSnapshots,
                serializedSnapshot -> jsonConverter.fromSerializedSnapshot(serializedSnapshot));
    }

    @Deprecated
    private List<CdoSnapshot> fetchCdoSnapshots(SnapshotFilter snapshotFilter, Optional<QueryParams> queryParams, Session session) {
        List<CdoSnapshotSerialized> serializedSnapshots = queryForCdoSnapshotDTOs(snapshotFilter, queryParams);

        if (queryParams.isPresent() && queryParams.get().isLoadCommitProps()) {
            List<CommitPropertyDTO> commitPropertyDTOs = commitPropertyFinder.findCommitPropertiesOfSnaphots(
                    serializedSnapshots.stream().map(it -> it.getCommitPk()).collect(toList()));

            cdoSnapshotsEnricher.enrichWithCommitProperties(serializedSnapshots, commitPropertyDTOs);
        }

        return Lists.transform(serializedSnapshots,
                serializedSnapshot -> jsonConverter.fromSerializedSnapshot(serializedSnapshot));
    }

    private List<CdoSnapshotSerialized> queryForCdoSnapshotDTOs(SnapshotFilter snapshotFilter, Optional<QueryParams> queryParams) {

        //TODO HOTSPOT
        System.out.println("--HOTSPOT-2-- fetchCdoSnapshots() " + snapshotFilter);

        SelectQuery query =  polyJDBC.query().select(snapshotFilter.select());
        snapshotFilter.addFrom(query);
        snapshotFilter.addWhere(query);
        if (queryParams.isPresent()) {
            snapshotFilter.applyQueryParams(query, queryParams.get());
        }
        query.orderBy(SNAPSHOT_PK, Order.DESC);
        return polyJDBC.queryRunner().queryList(query, cdoSnapshotMapper);
    }

    private Optional<Long> selectMaxSnapshotPrimaryKey(long globalIdPk, Session session) {

        Optional<Long> maxPrimaryKey =  session
                .select("MAX(" + SNAPSHOT_PK + ")")
                .from(tableNameProvider.getSnapshotTableNameWithSchema())
                .and(SNAPSHOT_GLOBAL_ID_FK, globalIdPk)
                .queryName("select max snapshot's PK")
                .queryForOptionalLong();

        if (maxPrimaryKey.isPresent() && maxPrimaryKey.get() == 0){
            return Optional.empty();
        }
        return maxPrimaryKey;
    }

    public void setJsonConverter(JsonConverter jsonConverter) {
        this.jsonConverter = jsonConverter;
    }
}