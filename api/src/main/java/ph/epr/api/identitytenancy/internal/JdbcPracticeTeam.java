package ph.epr.api.identitytenancy.internal;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import ph.epr.api.identitytenancy.*;

@Service
@Transactional
class JdbcPracticeTeam implements PracticeTeam {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private final JdbcClient jdbc;
    private final PasswordEncoder passwords;
    JdbcPracticeTeam(JdbcClient jdbc, PasswordEncoder passwords) { this.jdbc = jdbc; this.passwords = passwords; }

    private void platform() { jdbc.sql("SELECT set_config('epr.platform_operations','true',true)").query(String.class).single(); }
    private void lock(UUID practice) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 1))").param("scope", practice.toString()).query((r,n)->true).single();
    }
    @Override public UUID authorize(AuthenticatedActor actor, UUID practice) {
        if (actor == null || practice == null) throw new TeamException(403, "Practice administration is not available to this account.");
        jdbc.sql("SELECT set_config('epr.practice_id',:practice,true)").param("practice",practice.toString()).query(String.class).single();
        return jdbc.sql("""
            SELECT u.id FROM platform_user_account u
            JOIN practice_membership m ON m.user_id=u.id AND m.practice_id=:practice AND m.enabled
            JOIN practice_role_assignment r ON r.practice_id=m.practice_id AND r.user_id=m.user_id
                AND r.practice_role='PRACTICE_ADMINISTRATOR' AND r.enabled
            JOIN practice_tenant_registry p ON p.practice_id=m.practice_id
            WHERE u.username=:username AND u.enabled AND NOT u.password_reset_required
                AND u.role IN ('PRACTICE_STAFF','CLINICIAN')
                AND p.provisioning_status='ACTIVE' AND p.service_status='ENABLED'
            """).param("practice",practice).param("username",actor.username()).query(UUID.class).optional()
            .orElseThrow(()->new TeamException(403,"Practice administration is not available to this account."));
    }
    @Override public List<TeamScope> scopes(AuthenticatedActor actor) {
        platform();
        return jdbc.sql("""
            SELECT p.practice_id, p.display_name, COALESCE(c.enabled,false), p.schema_version
            FROM platform_user_account u JOIN practice_membership m ON m.user_id=u.id AND m.enabled
            JOIN practice_role_assignment r ON r.practice_id=m.practice_id AND r.user_id=m.user_id
                AND r.practice_role='PRACTICE_ADMINISTRATOR' AND r.enabled
            JOIN practice_tenant_registry p ON p.practice_id=m.practice_id
            LEFT JOIN practice_clinical_capability c ON c.practice_id=p.practice_id
            WHERE u.username=:username AND u.enabled AND NOT u.password_reset_required
                AND u.role IN ('PRACTICE_STAFF','CLINICIAN') AND p.provisioning_status='ACTIVE' AND p.service_status='ENABLED'
            ORDER BY p.display_name,p.practice_id
            """).param("username",actor.username()).query((r,n)->new ScopeRow(r.getObject(1,UUID.class),r.getString(2),r.getBoolean(3),r.getString(4))).list()
            .stream().map(row -> new TeamScope(row.practiceId(), row.practiceName(), row.clinicalEnabled(),
                row.schemaVersion(), readiness(row.practiceId()))).toList();
    }
    @Override public TeamPage members(AuthenticatedActor actor, UUID practice, String search, String role, String status, UUID site, int page, int size) {
        authorize(actor,practice);
        if (page<0 || page>100000 || size<1 || size>100 || search.length()>160
            || !Set.of("ALL","ENABLED","SUSPENDED").contains(status)
            || (!role.equals("ALL") && Arrays.stream(TeamRole.values()).noneMatch(r->r.name().equals(role))))
            throw new TeamException(422,"Invalid team filters or page size.");
        String filter = """
            FROM practice_membership m JOIN platform_user_account u ON u.id=m.user_id
            WHERE m.practice_id=:practice AND (strpos(lower(u.display_name),lower(:search))>0 OR strpos(u.username,lower(:search))>0)
            AND (:status='ALL' OR m.enabled=(:status='ENABLED'))
            AND (:role='ALL' OR EXISTS(SELECT 1 FROM practice_role_assignment r WHERE r.practice_id=m.practice_id AND r.user_id=m.user_id AND r.enabled AND r.practice_role=:role))
            AND (CAST(:site AS UUID) IS NULL OR EXISTS(SELECT 1 FROM practice_clinician_site_grant g WHERE g.practice_id=m.practice_id AND g.user_id=m.user_id AND g.enabled AND g.site_id=CAST(:site AS UUID)))
            """;
        var params = new HashMap<String,Object>(); params.put("practice",practice);params.put("search",search.strip());params.put("status",status);params.put("role",role);params.put("site",site==null?null:site.toString());
        var total=jdbc.sql("SELECT count(*) "+filter).params(params).query(Long.class).single();
        var ids=jdbc.sql("SELECT u.id "+filter+" ORDER BY lower(u.display_name),u.id LIMIT :size OFFSET :offset")
            .params(params).param("size",size).param("offset",page*size).query(UUID.class).list();
        return new TeamPage(ids.stream().map(id->read(practice,id)).toList(),total,page,size);
    }
    @Override public TeamMember member(AuthenticatedActor actor, UUID practice, UUID user) { authorize(actor,practice); return read(practice,user); }
    private TeamMember read(UUID practice, UUID user) {
        var roles=jdbc.sql("SELECT practice_role FROM practice_role_assignment WHERE practice_id=:p AND user_id=:u AND enabled ORDER BY practice_role")
            .param("p",practice).param("u",user).query(String.class).list().stream().map(TeamRole::valueOf).toList();
        var grants=jdbc.sql("SELECT site_id,reproductive_access FROM practice_clinician_site_grant WHERE practice_id=:p AND user_id=:u AND enabled ORDER BY site_id")
            .param("p",practice).param("u",user).query((r,n)->new TeamSiteGrant(r.getObject(1,UUID.class),r.getBoolean(2))).list();
        var listGrants=jdbc.sql("""
            SELECT site_id,source_kind,can_read,can_propose,can_write,can_review
            FROM practice_clinical_chart_grant
            WHERE practice_id=:p AND user_id=:u
              AND (can_read OR can_propose OR can_write OR can_review)
            ORDER BY site_id,source_kind
            """).param("p",practice).param("u",user).query((r,n)->new ClinicalListGrant(
                r.getObject(1,UUID.class),ClinicalListSource.valueOf(r.getString(2)),r.getBoolean(3),
                r.getBoolean(4),r.getBoolean(5),r.getBoolean(6))).list();
        return jdbc.sql("""
            SELECT u.id,u.display_name,u.username,u.enabled,u.password_reset_required,m.enabled,m.access_version,
                EXISTS(SELECT 1 FROM practice_member_onboarding o WHERE o.user_id=u.id AND o.practice_id=m.practice_id AND o.claimed_at IS NULL)
            FROM practice_membership m JOIN platform_user_account u ON u.id=m.user_id WHERE m.practice_id=:p AND m.user_id=:u
            """).param("p",practice).param("u",user).query((r,n)->new TeamMember(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getBoolean(4),r.getBoolean(5),r.getBoolean(6),r.getLong(7),roles,grants,listGrants,r.getBoolean(8)&&r.getBoolean(4)&&r.getBoolean(5)&&r.getBoolean(6),!grants.isEmpty()||!listGrants.isEmpty())).optional()
            .orElseThrow(()->new TeamException(404,"Team member not found in this Practice."));
    }
    @Override public TeamResolution resolve(AuthenticatedActor actor, UUID practice, String email) {
        var actorId=authorize(actor,practice); var normalized=email(email);
        var attempts=jdbc.sql("""
            INSERT INTO practice_member_resolution_limit(practice_id,actor_id,attempts) VALUES(:p,:a,1)
            ON CONFLICT(practice_id,actor_id) DO UPDATE SET
                attempts=CASE WHEN practice_member_resolution_limit.window_start < CURRENT_TIMESTAMP - INTERVAL '1 minute' THEN 1 ELSE practice_member_resolution_limit.attempts+1 END,
                window_start=CASE WHEN practice_member_resolution_limit.window_start < CURRENT_TIMESTAMP - INTERVAL '1 minute' THEN CURRENT_TIMESTAMP ELSE practice_member_resolution_limit.window_start END
            RETURNING attempts
            """).param("p",practice).param("a",actorId).query(Integer.class).single();
        // Return a neutral result after the budget is exhausted; commit the limiter even on denial.
        if(attempts>20) return new TeamResolution(null,"Try again in a minute.",normalized,true);
        var account=jdbc.sql("SELECT id,display_name FROM platform_user_account WHERE username=:email AND enabled AND NOT password_reset_required AND role IN ('PRACTICE_STAFF','CLINICIAN')")
            .param("email",normalized).query((r,n)->new TeamResolution(r.getObject(1,UUID.class),r.getString(2),normalized,true)).optional();
        if(account.isPresent()) return account.get();
        boolean exists=jdbc.sql("SELECT EXISTS(SELECT 1 FROM platform_user_account WHERE username=:email)").param("email",normalized).query(Boolean.class).single();
        return new TeamResolution(null,exists?"This account cannot be linked. Contact its administrator.":"",normalized,exists);
    }
    @Override public TeamResult create(AuthenticatedActor actor, UUID practice, UUID key, TeamCreate command, Set<UUID> verifiedSites) {
        lock(practice);var actorId=authorize(actor,practice); platform();
        var replay=replay(practice,actorId,key,"CREATE",command);if(replay!=null)return replay;
        validate(command.roles(),command.siteGrants(),command.clinicalListGrants(),command.reason(),verifiedSites);
        String email=email(command.email());
        if(command.displayName()==null || command.displayName().strip().isEmpty() || command.displayName().length()>160)throw new TeamException(422,"Enter a display name of up to 160 characters.");
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:email,0))").param("email",email).query((r,n)->true).single();
        var existing=jdbc.sql("SELECT id FROM platform_user_account WHERE username=:email FOR UPDATE").param("email",email).query(UUID.class).optional();
        UUID user;String password=null;
        if(existing.isPresent()) {
            user=existing.get();
            boolean eligible=jdbc.sql("SELECT enabled AND NOT password_reset_required AND role IN ('PRACTICE_STAFF','CLINICIAN') AND lower(display_name)=lower(:name) FROM platform_user_account WHERE id=:u")
                .param("name",command.displayName().strip()).param("u",user).query(Boolean.class).single();
            if(!eligible || !user.equals(command.confirmedUserId()))throw new TeamException(409,"Resolve and confirm the existing account before linking it. Its identity and credentials will be preserved.");
        } else {
            if(command.confirmedUserId()!=null)throw new TeamException(409,"The confirmed account changed. Resolve the identity again.");
            user=UUID.randomUUID();password=temporaryPassword();
            jdbc.sql("""
                INSERT INTO platform_user_account(id,username,display_name,password_hash,role,enabled,password_reset_required,created_at,updated_at)
                VALUES(:u,:email,:name,:hash,'PRACTICE_STAFF',true,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
                """).param("u",user).param("email",email).param("name",command.displayName().strip()).param("hash",passwords.encode(password)).update();
            jdbc.sql("INSERT INTO practice_member_onboarding(user_id,practice_id) VALUES(:u,:p)").param("u",user).param("p",practice).update();
        }
        if(jdbc.sql("SELECT EXISTS(SELECT 1 FROM practice_membership WHERE practice_id=:p AND user_id=:u)").param("p",practice).param("u",user).query(Boolean.class).single())throw new TeamException(409,"This account is already a member. Open the member to edit access.");
        jdbc.sql("INSERT INTO practice_membership(practice_id,user_id,enabled,created_at,updated_at) VALUES(:p,:u,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)").param("p",practice).param("u",user).update();
        requireClinicalReadiness(practice,command.clinicalListGrants());
        saveAccess(practice,user,actorId,true,command.roles(),command.siteGrants(),command.clinicalListGrants(),command.reason());
        var member=read(practice,user);
        record(practice,user,actorId,key,"CREATE",command,command.reason(),null,member);
        return new TeamResult(member,password,false);
    }
    @Override public TeamResult update(AuthenticatedActor actor, UUID practice, UUID user, UUID key, TeamAccess command, Set<UUID> verifiedSites) {
        lock(practice);var actorId=authorize(actor,practice);platform();
        var operation="ACCESS/"+user;var replay=replay(practice,actorId,key,operation,command);if(replay!=null)return replay;
        validate(command.roles(),command.siteGrants(),command.clinicalListGrants(),command.reason(),verifiedSites);
        var before=read(practice,user);version(before,command.expectedVersion());
        if(!before.accountEnabled())throw new TeamException(409,"The account is disabled. Practice access cannot reactivate an account.");
        if(before.roles().contains(TeamRole.PRACTICE_ADMINISTRATOR) && before.enabled() && (!command.enabled() || !command.roles().contains(TeamRole.PRACTICE_ADMINISTRATOR))) {
            var others=jdbc.sql("""
                SELECT count(*) FROM practice_membership m JOIN practice_role_assignment r ON r.practice_id=m.practice_id AND r.user_id=m.user_id
                JOIN platform_user_account u ON u.id=m.user_id
                WHERE m.practice_id=:p AND m.user_id<>:u AND m.enabled AND r.enabled AND r.practice_role='PRACTICE_ADMINISTRATOR'
                AND u.enabled AND NOT u.password_reset_required AND u.role IN ('CLINICIAN','PRACTICE_STAFF')
                """).param("p",practice).param("u",user).query(Long.class).single();
            if(others==0)throw new TeamException(409,"Keep at least one enabled administrator who has completed account setup.");
        }
        // No incomplete preflight can establish safe clinical offboarding. Fail closed until the
        // owning modules support fenced draining, unsigned notes and follow-up reassignment.
        if(!before.siteGrants().isEmpty() && (!command.enabled() || !command.roles().contains(TeamRole.CLINICIAN)
            || before.siteGrants().stream().anyMatch(g->command.siteGrants().stream().noneMatch(n->n.siteId().equals(g.siteId()) && (!g.reproductiveAccess() || n.reproductiveAccess())))))
            throw new TeamException(409,"Clinical access reduction requires the care-continuity offboarding workflow, which is not available yet. Existing ownership and access were preserved.");
        if(isClinicalListReduction(before.clinicalListGrants(),command.clinicalListGrants()))
            throw new TeamException(409,"Clinical-list access reduction requires the care-continuity offboarding workflow, which is not available yet. Existing access was preserved.");
        if(!command.enabled() && !command.siteGrants().isEmpty())throw new TeamException(422,"Suspended membership cannot include enabled clinical site grants.");
        requireClinicalReadiness(practice,command.clinicalListGrants());
        saveAccess(practice,user,actorId,command.enabled(),command.roles(),command.siteGrants(),command.clinicalListGrants(),command.reason());
        var after=read(practice,user);record(practice,user,actorId,key,operation,command,command.reason(),before,after);
        return new TeamResult(after,null,false);
    }
    @Override public TeamResult reissue(AuthenticatedActor actor, UUID practice, UUID user, UUID key, TeamPassword command) {
        lock(practice);var actorId=authorize(actor,practice);platform();
        String operation="PASSWORD/"+user;var replay=replay(practice,actorId,key,operation,command);if(replay!=null)return replay;
        reason(command.reason());
        jdbc.sql("SELECT id FROM platform_user_account WHERE id=:u FOR UPDATE").param("u",user).query(UUID.class).optional().orElseThrow(()->new TeamException(404,"Member not found."));
        var before=read(practice,user);version(before,command.expectedVersion());
        if(!before.canReissue())throw new TeamException(409,"Only an unclaimed account created by this Practice can receive a replacement temporary password.");
        var password=temporaryPassword();
        jdbc.sql("UPDATE platform_user_account SET password_hash=:hash,updated_at=CURRENT_TIMESTAMP WHERE id=:u").param("hash",passwords.encode(password)).param("u",user).update();
        jdbc.sql("UPDATE practice_membership SET access_version=access_version+1,updated_at=CURRENT_TIMESTAMP WHERE practice_id=:p AND user_id=:u").param("p",practice).param("u",user).update();
        var after=read(practice,user);record(practice,user,actorId,key,operation,command,command.reason(),before,after);
        return new TeamResult(after,password,false);
    }
    @Override public List<TeamEvent> events(AuthenticatedActor actor, UUID practice, UUID user, int page) {
        authorize(actor,practice);read(practice,user);if(page<0 || page>100000)throw new TeamException(422,"Invalid history page.");
        return jdbc.sql("""
            SELECT e.id,u.display_name,e.action,e.reason,e.before_access::text,e.after_access::text,e.occurred_at
            FROM practice_member_event e JOIN platform_user_account u ON u.id=e.actor_id
            WHERE e.practice_id=:p AND e.user_id=:u ORDER BY e.occurred_at DESC,e.id LIMIT 25 OFFSET :offset
            """).param("p",practice).param("u",user).param("offset",page*25)
            .query((r,n)->new TeamEvent(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getObject(7,java.time.OffsetDateTime.class))).list();
    }
    private void saveAccess(UUID practice,UUID user,UUID actor,boolean enabled,List<TeamRole> roles,List<TeamSiteGrant> grants,List<ClinicalListGrant> listGrants,String reason) {
        jdbc.sql("UPDATE practice_membership SET enabled=:enabled,access_version=access_version+1,updated_at=CURRENT_TIMESTAMP WHERE practice_id=:p AND user_id=:u").param("enabled",enabled).param("p",practice).param("u",user).update();
        jdbc.sql("UPDATE practice_role_assignment SET enabled=false,updated_at=CURRENT_TIMESTAMP WHERE practice_id=:p AND user_id=:u").param("p",practice).param("u",user).update();
        for(var role:roles) jdbc.sql("""
            INSERT INTO practice_role_assignment(practice_id,user_id,practice_role,enabled,created_at,updated_at) VALUES(:p,:u,:role,true,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)
            ON CONFLICT(practice_id,user_id,practice_role) DO UPDATE SET enabled=true,updated_at=CURRENT_TIMESTAMP
            """).param("p",practice).param("u",user).param("role",role.name()).update();
        for(var grant:grants) jdbc.sql("""
            INSERT INTO practice_clinician_site_grant(practice_id,user_id,site_id,enabled,reproductive_access,reason,granted_by,patient_directory_read,patient_detail_read,patient_register,patient_demographic_edit)
            VALUES(:p,:u,:s,true,:reproductive,:reason,:actor,true,true,true,true)
            ON CONFLICT(practice_id,user_id,site_id) DO UPDATE SET enabled=true,reproductive_access=:reproductive,reason=:reason,granted_by=:actor,granted_at=CURRENT_TIMESTAMP,
                patient_directory_read=true,patient_detail_read=true,patient_register=true,patient_demographic_edit=true
            """).param("p",practice).param("u",user).param("s",grant.siteId()).param("reproductive",grant.reproductiveAccess()).param("reason",reason.strip()).param("actor",actor).update();
        for(var grant:listGrants) {
            if(!hasAnyAction(grant)) continue;
            jdbc.sql("""
                INSERT INTO practice_clinical_chart_grant(
                    practice_id,user_id,site_id,source_kind,can_read,can_propose,can_write,can_review,reason,granted_at
                ) VALUES(:p,:u,:s,:kind,:read,:propose,:write,:review,:reason,CURRENT_TIMESTAMP)
                ON CONFLICT(practice_id,user_id,site_id,source_kind) DO UPDATE SET
                    can_read=:read,can_propose=:propose,can_write=:write,can_review=:review,
                    reason=:reason,granted_at=CURRENT_TIMESTAMP
                """).param("p",practice).param("u",user).param("s",grant.siteId()).param("kind",grant.sourceKind().name())
                .param("read",grant.read()).param("propose",grant.propose()).param("write",grant.write())
                .param("review",grant.review()).param("reason",reason.strip()).update();
        }
    }
    private void validate(List<TeamRole> roles,List<TeamSiteGrant> grants,List<ClinicalListGrant> listGrants,String reason,Set<UUID> sites) {
        reason(reason);
        if(roles==null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull) || roles.size()>4 || new HashSet<>(roles).size()!=roles.size())throw new TeamException(422,"Select at least one supported Practice role, without duplicates.");
        if(grants==null || grants.size()>100 || grants.stream().anyMatch(g->g==null || g.siteId()==null || !sites.contains(g.siteId())) || grants.stream().map(TeamSiteGrant::siteId).distinct().count()!=grants.size())throw new TeamException(422,"Choose distinct sites that belong to this Practice.");
        if(listGrants==null || listGrants.size()>300 || listGrants.stream().anyMatch(g->g==null || g.siteId()==null || g.sourceKind()==null)
            || listGrants.stream().map(g->g.siteId()+"/"+g.sourceKind()).distinct().count()!=listGrants.size())
            throw new TeamException(422,"Choose one supported clinical-list profile per selected site and source.");
        if(!roles.contains(TeamRole.CLINICIAN) && !grants.isEmpty())throw new TeamException(422,"Clinical site access requires the clinician role.");
        if(roles.contains(TeamRole.CLINICIAN) && grants.isEmpty())throw new TeamException(422,"Select at least one clinical site for the clinician role.");
        var grantSites=grants.stream().map(TeamSiteGrant::siteId).collect(java.util.stream.Collectors.toSet());
        if(listGrants.stream().anyMatch(g->!grantSites.contains(g.siteId())))throw new TeamException(422,"Clinical-list access requires an enabled clinical grant for the same site.");
        if(listGrants.stream().anyMatch(g->!validLattice(g)))throw new TeamException(422,"Choose a supported clinical-list permission profile.");
        if(listGrants.stream().anyMatch(this::hasAnyAction) && !roles.contains(TeamRole.CLINICIAN))throw new TeamException(422,"Clinical-list access requires the clinician role.");
    }

    private boolean validLattice(ClinicalListGrant grant) {
        return !grant.propose() && !grant.write() && !grant.review() && !grant.read()
            || grant.read() && !grant.propose() && !grant.write() && !grant.review()
            || grant.read() && grant.propose() && !grant.write() && !grant.review()
            || grant.read() && grant.propose() && grant.write() && !grant.review()
            || grant.read() && grant.propose() && grant.write() && grant.review();
    }
    private boolean hasAnyAction(ClinicalListGrant grant) { return grant.read() || grant.propose() || grant.write() || grant.review(); }
    private boolean isClinicalListReduction(List<ClinicalListGrant> before,List<ClinicalListGrant> requested) {
        return before.stream().anyMatch(current -> {
            var next=requested.stream().filter(candidate -> candidate.siteId().equals(current.siteId()) && candidate.sourceKind()==current.sourceKind()).findFirst().orElse(null);
            return next==null || current.read()&&!next.read() || current.propose()&&!next.propose()
                || current.write()&&!next.write() || current.review()&&!next.review();
        });
    }
    private void requireClinicalReadiness(UUID practice,List<ClinicalListGrant> grants) {
        if(grants.stream().noneMatch(this::hasAnyAction))return;
        var readiness=readiness(practice);
        if(readiness.state()!=ClinicalReadiness.State.READY)throw new TeamException(409,"Clinical-list access cannot be granted until Practice clinical readiness is READY.");
    }

    private ClinicalReadiness readiness(UUID practice) {
        var state=jdbc.sql("""
            SELECT p.schema_version,p.provisioning_status,COALESCE(c.enabled,false),r.state,p.failure_message
            FROM practice_tenant_registry p
            LEFT JOIN practice_clinical_capability c ON c.practice_id=p.practice_id
            LEFT JOIN clinical_schema_rollout r ON r.practice_id=p.practice_id
            WHERE p.practice_id=:p
            """).param("p",practice).query((r,n)->new ReadinessRow(r.getString(1),r.getString(2),r.getBoolean(3),r.getString(4),r.getString(5))).single();
        var capabilities=jdbc.sql("SELECT source_kind,enabled FROM practice_clinical_chart_capability WHERE practice_id=:p ORDER BY source_kind")
            .param("p",practice).query((r,n)->new ClinicalReadiness.SourceCapability(ClinicalReadiness.SourceKind.valueOf(r.getString(1)),r.getBoolean(2))).list();
        var failure=state.failure();
        if(failure==null && "QUARANTINED".equals(state.rolloutState()))failure="The most recent tenant schema upgrade was quarantined.";
        return ClinicalReadiness.evaluate(state.schemaVersion(),state.rolloutState(),state.clinicalEnabled(),capabilities,
            "QUARANTINED".equals(state.provisioningStatus()),failure);
    }
    private void reason(String value){if(value==null || value.strip().isEmpty() || value.length()>500)throw new TeamException(422,"Enter a reason of up to 500 characters.");}
    private String email(String value){if(value==null || value.length()>160 || !EMAIL.matcher(value.strip()).matches())throw new TeamException(422,"Enter a valid account email.");return value.strip().toLowerCase(Locale.ROOT);}
    private void version(TeamMember member,long expected){if(member.version()!=expected)throw new TeamException(409,"This member changed after it was loaded. Review the latest access before saving again.");}
    private TeamResult replay(UUID practice,UUID actor,UUID key,String operation,Object command){
        if(key==null)throw new TeamException(422,"An idempotency key is required.");
        return jdbc.sql("SELECT operation,fingerprint,result::text FROM practice_member_command WHERE practice_id=:p AND actor_id=:a AND command_key=:key")
            .param("p",practice).param("a",actor).param("key",key).query((r,n)->{
                if(!operation.equals(r.getString(1)) || !JSON.writeValueAsString(command).equals(r.getString(2)))throw new TeamException(409,"This retry key was used with different details. Review the original request.");
                var stored=JSON.readValue(r.getString(3),TeamMember.class);
                var normalized=stored.clinicalListGrants()==null ? new TeamMember(stored.userId(),stored.displayName(),stored.username(),stored.accountEnabled(),
                    stored.setupRequired(),stored.enabled(),stored.version(),stored.roles(),stored.siteGrants(),List.of(),stored.canReissue(),stored.clinicalRemovalBlocked()) : stored;
                return new TeamResult(normalized,null,true);
            }).optional().orElse(null);
    }
    private void record(UUID practice,UUID user,UUID actor,UUID key,String operation,Object command,String reason,TeamMember before,TeamMember after){
        jdbc.sql("INSERT INTO practice_member_event(id,practice_id,user_id,actor_id,action,reason,before_access,after_access) VALUES(:id,:p,:u,:a,:action,:reason,CAST(:before AS jsonb),CAST(:after AS jsonb))")
            .param("id",UUID.randomUUID()).param("p",practice).param("u",user).param("a",actor).param("action",operation.split("/")[0]).param("reason",reason.strip()).param("before",JSON.writeValueAsString(before)).param("after",JSON.writeValueAsString(after)).update();
        jdbc.sql("INSERT INTO practice_member_command(practice_id,actor_id,command_key,operation,fingerprint,result) VALUES(:p,:a,:key,:operation,:fingerprint,CAST(:result AS jsonb))")
            .param("p",practice).param("a",actor).param("key",key).param("operation",operation).param("fingerprint",JSON.writeValueAsString(command)).param("result",JSON.writeValueAsString(after)).update();
    }
    private String temporaryPassword(){return "Aa9!"+UUID.randomUUID().toString().replace("-","");}
    private record ScopeRow(UUID practiceId,String practiceName,boolean clinicalEnabled,String schemaVersion) {}
    private record ReadinessRow(String schemaVersion,String provisioningStatus,boolean clinicalEnabled,String rolloutState,String failure) {}
}
