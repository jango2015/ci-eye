package org.netmelody.cii.witness.teamcity;

import static com.google.common.collect.Collections2.filter;
import static com.google.common.collect.Collections2.transform;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.netmelody.cii.domain.Feature;
import org.netmelody.cii.domain.Sponsor;
import org.netmelody.cii.domain.Status;
import org.netmelody.cii.domain.Target;
import org.netmelody.cii.domain.TargetGroup;
import org.netmelody.cii.persistence.Detective;
import org.netmelody.cii.witness.Witness;
import org.netmelody.cii.witness.protocol.RestRequester;
import org.netmelody.cii.witness.teamcity.jsondomain.Build;
import org.netmelody.cii.witness.teamcity.jsondomain.BuildDetail;
import org.netmelody.cii.witness.teamcity.jsondomain.BuildType;
import org.netmelody.cii.witness.teamcity.jsondomain.BuildTypeDetail;
import org.netmelody.cii.witness.teamcity.jsondomain.Builds;
import org.netmelody.cii.witness.teamcity.jsondomain.Change;
import org.netmelody.cii.witness.teamcity.jsondomain.ChangeDetail;
import org.netmelody.cii.witness.teamcity.jsondomain.ChangesMany;
import org.netmelody.cii.witness.teamcity.jsondomain.ChangesOne;
import org.netmelody.cii.witness.teamcity.jsondomain.Project;
import org.netmelody.cii.witness.teamcity.jsondomain.ProjectDetail;
import org.netmelody.cii.witness.teamcity.jsondomain.TeamCityProjects;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class TeamCityWitness implements Witness {

    private final Gson json = new GsonBuilder().create();
    private final RestRequester restRequester = new RestRequester();
    private final String endpoint;

    public TeamCityWitness(String endpoint) {
        this.endpoint = endpoint;
    }

    public static void main(String[] args) {
        final Feature feature = new Feature("HIP - Trunk", "http://teamcity-server:8111");
        final TeamCityWitness witness = new TeamCityWitness(feature.endpoint());
        witness.statusOf(feature);
    }
    
    @Override
    public TargetGroup statusOf(final Feature feature) {
        restRequester.makeRequest(endpoint + "/guestAuth/");
        if (!endpoint.equals(feature.endpoint())) {
            return new TargetGroup();
        }
        
        final Project project = filter(projects(), new Predicate<Project>() {
            @Override public boolean apply(Project project) {
                return project.name.startsWith(feature.name());
            }
        }).iterator().next();
        
        final Collection<Target> targets = transform(buildTypesFor(project), new Function<BuildType, Target>() {
            @Override public Target apply(BuildType buildType) {
                return targetFrom(buildType);
            }
        });
        
        return new TargetGroup(targets);
    }
    
    @Override
    public long millisecondsUntilNextUpdate() {
        return 0L;
    }
        
    private Collection<Project> projects() {
        return makeTeamCityRestCall(endpoint + "/app/rest/projects", TeamCityProjects.class).project;
    }

    private Collection<BuildType> buildTypesFor(Project projectDigest) {
        return makeTeamCityRestCall(endpoint + projectDigest.href, ProjectDetail.class).buildTypes.buildType;
    }
    
    private Target targetFrom(BuildType buildType) {
        final BuildTypeDetail buildTypeDetail = makeTeamCityRestCall(endpoint + buildType.href, BuildTypeDetail.class);
        
        if (buildTypeDetail.paused) {
            return new Target(buildType.id, buildType.name, Status.DISABLED);
        }
        
        final Builds builds = makeTeamCityRestCall(endpoint + buildTypeDetail.builds.href, Builds.class);

        if (builds.build == null || builds.build.isEmpty()) {
            return new Target(buildType.id, buildType.name, Status.GREEN);
        }
        
        final Build lastBuild = builds.build.iterator().next();
        final BuildDetail lastBuildDetail = makeTeamCityRestCall(endpoint + lastBuild.href, BuildDetail.class);
        
        final List<Sponsor> sponsors = sponsorsOf(lastBuildDetail);
        
        return new Target(buildType.id, buildType.name, lastBuildDetail.status(), sponsors);
    }
    
    private List<Sponsor> sponsorsOf(BuildDetail build) {
        return new Detective().sponsorsOf(analyseChanges(build));
    }

    private String analyseChanges(BuildDetail build) {
        if (build.changes == null) {
            return "";
        }
        
        final List<Change> changes = new ArrayList<Change>();
        if (build.changes.count == 1) {
            changes.add(makeTeamCityRestCall(endpoint + build.changes.href, ChangesOne.class).change);
        }
        else {
            changes.addAll(makeTeamCityRestCall(endpoint + build.changes.href, ChangesMany.class).change);
        }
        
        final StringBuilder result = new StringBuilder();
        for (Change change : changes) {
            final ChangeDetail changeDetail = makeTeamCityRestCall(endpoint + change.href, ChangeDetail.class);
            result.append(changeDetail.comment);
            result.append(changeDetail.username);
        }
        
        return result.toString();
    }

    private <T> T makeTeamCityRestCall(String url, Class<T> type) {
        return json.fromJson(restRequester.makeRequest(url).replace("\"@", "\""), type);
    }
}
