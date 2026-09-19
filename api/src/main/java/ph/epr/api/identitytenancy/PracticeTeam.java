package ph.epr.api.identitytenancy;

import java.time.OffsetDateTime;
import java.util.*;

/** Identity-owned administration. Administrative authority never supplies a clinical context. */
public interface PracticeTeam {
    enum TeamRole { PRACTICE_ADMINISTRATOR, CLINICIAN, NURSE, SECRETARY }
    record TeamScope(UUID practiceId, String practiceName, boolean clinicalEnabled, String schemaVersion) {}
    record TeamSiteGrant(UUID siteId, boolean reproductiveAccess) {}
    record TeamMember(UUID userId, String displayName, String username, boolean accountEnabled,
        boolean setupRequired, boolean enabled, long version, List<TeamRole> roles,
        List<TeamSiteGrant> siteGrants, boolean canReissue, boolean clinicalRemovalBlocked) {}
    record TeamPage(List<TeamMember> items, long total, int page, int size) {}
    record TeamResolution(UUID userId, String displayName, String username, boolean existing) {}
    record TeamCreate(String email, String displayName, UUID confirmedUserId, List<TeamRole> roles,
        List<TeamSiteGrant> siteGrants, String reason) {}
    record TeamAccess(long expectedVersion, boolean enabled, List<TeamRole> roles,
        List<TeamSiteGrant> siteGrants, String reason) {}
    record TeamPassword(long expectedVersion, String reason) {}
    record TeamResult(TeamMember member, String temporaryPassword, boolean replayed) {}
    record TeamEvent(UUID id, String actorName, String action, String reason, String beforeAccess,
        String afterAccess, OffsetDateTime occurredAt) {}
    List<TeamScope> scopes(AuthenticatedActor actor);
    UUID authorize(AuthenticatedActor actor, UUID practice);
    TeamPage members(AuthenticatedActor actor, UUID practice, String search, String role, String status, UUID site, int page, int size);
    TeamMember member(AuthenticatedActor actor, UUID practice, UUID user);
    TeamResolution resolve(AuthenticatedActor actor, UUID practice, String email);
    TeamResult create(AuthenticatedActor actor, UUID practice, UUID key, TeamCreate command, Set<UUID> verifiedSites);
    TeamResult update(AuthenticatedActor actor, UUID practice, UUID user, UUID key, TeamAccess command, Set<UUID> verifiedSites);
    TeamResult reissue(AuthenticatedActor actor, UUID practice, UUID user, UUID key, TeamPassword command);
    List<TeamEvent> events(AuthenticatedActor actor, UUID practice, UUID user, int page);
}
