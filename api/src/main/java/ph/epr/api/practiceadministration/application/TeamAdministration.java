package ph.epr.api.practiceadministration.application;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import ph.epr.api.identitytenancy.*;
import ph.epr.api.platformoperations.PracticeSiteDirectory;

@Service
@Profile("local")
public class TeamAdministration {
    private final PracticeTeam team;
    private final PracticeSiteDirectory sites;
    public TeamAdministration(PracticeTeam team, PracticeSiteDirectory sites) { this.team=team;this.sites=sites; }
    public PracticeTeam.TeamResult create(AuthenticatedActor actor,UUID practice,UUID key,PracticeTeam.TeamCreate command) {
        return team.create(actor,practice,key,command,verified(actor,practice,command.siteGrants()));
    }
    public PracticeTeam.TeamResult update(AuthenticatedActor actor,UUID practice,UUID user,UUID key,PracticeTeam.TeamAccess command) {
        return team.update(actor,practice,user,key,command,verified(actor,practice,command.siteGrants()));
    }
    private Set<UUID> verified(AuthenticatedActor actor, UUID practice,List<PracticeTeam.TeamSiteGrant> grants) {
        team.authorize(actor,practice);
        if(grants==null || grants.isEmpty())return Set.of();
        return sites.sites(actor,practice).stream().map(PracticeSiteDirectory.TeamSite::siteId).collect(Collectors.toSet());
    }
}
