package de.hsh.favs.sharewise.groups.internal.persistence;

import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUPS_;
import static de.hsh.favs.sharewise.groups.internal.persistence.generated.Tables.GROUP_MEMBERS;

import de.hsh.favs.sharewise.groups.internal.application.group.GroupCreationPort;
import de.hsh.favs.sharewise.groups.internal.application.group.GroupDetails;
import de.hsh.favs.sharewise.groups.internal.domain.GroupDescription;
import de.hsh.favs.sharewise.groups.internal.domain.GroupMetadata;
import de.hsh.favs.sharewise.groups.internal.domain.GroupRole;
import de.hsh.favs.sharewise.groups.internal.persistence.generated.tables.records.GroupsRecord;
import de.hsh.favs.sharewise.shared.infra.persistence.jooq.JooqAccess;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Repository;

/**
 * Inserts a group and its creator's {@code admin} membership in the caller's segment. The
 * membership FK to {@code groups.users} rejects an unknown creator, failing the whole segment.
 */
@Repository
@Scope("operation")
class JooqGroupCreation implements GroupCreationPort {

    private final JooqAccess database;

    JooqGroupCreation(JooqAccess database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public GroupDetails createWithAdministrator(UUID creatorId, GroupMetadata metadata) {
        Objects.requireNonNull(creatorId, "creatorId");
        Objects.requireNonNull(metadata, "metadata");
        DSLContext db = database.dsl();
        GroupsRecord group = db.insertInto(GROUPS_)
                .set(GROUPS_.NAME, metadata.name().value())
                .set(GROUPS_.DESCRIPTION, metadata.description().map(GroupDescription::value).orElse(null))
                .returning(GROUPS_.ID, GROUPS_.CREATED_AT, GROUPS_.VERSION)
                .fetchSingle();
        db.insertInto(GROUP_MEMBERS)
                .set(GROUP_MEMBERS.GROUP_ID, group.getId())
                .set(GROUP_MEMBERS.USER_ID, creatorId)
                .set(GROUP_MEMBERS.ROLE, GroupProjections.toDatabase(GroupRole.ADMIN))
                .execute();
        return new GroupDetails(group.getId(), metadata, group.getCreatedAt().toInstant(), group.getVersion(), 1,
                GroupRole.ADMIN);
    }
}
