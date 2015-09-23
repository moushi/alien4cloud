package alien4cloud.orchestrators.locations.services;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Resource;
import javax.inject.Inject;

import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.stereotype.Service;

import alien4cloud.component.ICSARRepositorySearchService;
import alien4cloud.csar.services.CsarService;
import alien4cloud.dao.IGenericSearchDAO;
import alien4cloud.dao.model.GetMultipleDataResult;
import alien4cloud.exception.NotFoundException;
import alien4cloud.model.common.Usage;
import alien4cloud.model.components.CSARDependency;
import alien4cloud.model.components.Csar;
import alien4cloud.model.components.IndexedNodeType;
import alien4cloud.model.deployment.Deployment;
import alien4cloud.model.orchestrators.Orchestrator;
import alien4cloud.model.orchestrators.OrchestratorState;
import alien4cloud.model.orchestrators.locations.Location;
import alien4cloud.model.orchestrators.locations.LocationResourceTemplate;
import alien4cloud.orchestrators.plugin.ILocationConfiguratorPlugin;
import alien4cloud.orchestrators.plugin.ILocationResourceAccessor;
import alien4cloud.orchestrators.plugin.IOrchestratorPlugin;
import alien4cloud.orchestrators.services.OrchestratorService;
import alien4cloud.paas.OrchestratorPluginService;
import alien4cloud.utils.MapUtil;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Manages a locations.
 */
@Slf4j
@Service
public class LocationService {
    @Resource(name = "alien-es-dao")
    private IGenericSearchDAO alienDAO;
    @Inject
    private OrchestratorPluginService orchestratorPluginService;
    @Inject
    private OrchestratorService orchestratorService;
    @Inject
    private LocationArchiveIndexer locationArchiveIndexer;
    @Inject
    private LocationResourceService locationResourceService;
    @Resource
    private ICSARRepositorySearchService csarRepoSearchService;
    @Resource
    private CsarService csarService;

    /**
     * Add a new locations for a given orchestrator.
     */
    public String create(String orchestratorId, String locationName, String infrastructureType) {
        Orchestrator orchestrator = orchestratorService.getOrFail(orchestratorId);
        if (!OrchestratorState.CONNECTED.equals(orchestrator.getState())) {
            // we cannot configure locations for orchestrator that are not connected.
            // TODO throw exception
        }
        // checks that the infrastructure type is valid
        Location location = new Location();
        location.setId(UUID.randomUUID().toString());
        location.setName(locationName);
        location.setOrchestratorId(orchestratorId);
        location.setInfrastructureType(infrastructureType);
        // TODO add User and Group manage by the Orchestrator security

        Set<CSARDependency> dependencies = locationArchiveIndexer.indexArchives(orchestrator, location);
        location.setDependencies(dependencies);

        // save the new location
        alienDAO.save(location);

        autoConfigure(orchestrator, location);

        return location.getId();
    }

    /**
     * Trigger plugin auto-configuration for the given location.
     *
     * @param locationId Id of the location.
     */
    public List<LocationResourceTemplate> autoConfigure(String locationId) {
        Location location = getOrFail(locationId);
        Orchestrator orchestrator = orchestratorService.getOrFail(location.getOrchestratorId());

        List<LocationResourceTemplate> generatedLocationResources = autoConfigure(orchestrator, location);

        if (CollectionUtils.isEmpty(generatedLocationResources)) {
            // if the orchestrator doesn't support auto-configuration
            // TODO throw exception or just return false ?
        }

        return generatedLocationResources;
    }

    /**
     * This method calls the orchestrator plugin to try to auto-configure the
     *
     * @param orchestrator The orchestrator for which to auto-configure a location.
     * @param location The location to auto-configure
     * @return the List of {@link LocationResourceTemplate} generated from the location auto-configuration call, null is a valid answer.
     */
    private List<LocationResourceTemplate> autoConfigure(Orchestrator orchestrator, Location location) {
        // get the orchestrator plugin instance
        IOrchestratorPlugin orchestratorInstance = (IOrchestratorPlugin) orchestratorPluginService.getOrFail(orchestrator.getId());
        ILocationConfiguratorPlugin configuratorPlugin = orchestratorInstance.getConfigurator(location.getInfrastructureType());

        ILocationResourceAccessor accessor = locationResourceService.accessor(location.getId());

        // let's try to auto-configure the location
        List<LocationResourceTemplate> templates = configuratorPlugin.instances(accessor);

        if (templates != null) {
            // save the instances
            for (LocationResourceTemplate template : templates) {
                // initialize the instances from data.
                template.setId(UUID.randomUUID().toString());
                template.setLocationId(location.getId());
                template.setGenerated(true);
                template.setEnabled(true);
                IndexedNodeType nodeType = csarRepoSearchService.getRequiredElementInDependencies(IndexedNodeType.class, template.getTemplate().getType(),
                        location.getDependencies());
                nodeType.getDerivedFrom().add(0, template.getTemplate().getType());
                template.setTypes(nodeType.getDerivedFrom());
            }
            alienDAO.save(templates.toArray(new LocationResourceTemplate[templates.size()]));
        }
        return templates;
    }

    /**
     * Get the location matching the given id or throw a NotFoundException
     *
     * @param id If of the location that we want to get.
     * @return An instance of the location.
     */
    public Location getOrFail(String id) {
        Location location = alienDAO.findById(Location.class, id);
        if (location == null) {
            throw new NotFoundException("Location [" + id + "] doesn't exists.");
        }
        return location;
    }

    /**
     * Return all locations for a given orchestrator.
     *
     * @param orchestratorId The id of the orchestrator for which to get locations.
     * @return
     */
    public List<Location> getAll(String orchestratorId) {
        List<Location> locations = alienDAO.customFindAll(Location.class, QueryBuilders.termQuery("orchestratorId", orchestratorId));
        if (locations == null) {
            return Lists.newArrayList();
        }
        return locations;
    }

    /**
     * Delete a locations.
     *
     * @param id id of the locations to delete.
     * @return true if the location was successfully , false if not.
     */
    public boolean delete(String id) {
        Map<String, String[]> filters = getDeploymentFilterPerLocation(true, id);
        long count = alienDAO.count(Deployment.class, null, filters);
        if (count > 0) {
            return false;
        }
        Location location = getOrFail(id);

        // delete all archives associated with this location only
        // Only delete the archives if there is no more location of this type
        filters.clear();
        addFilter(filters, "orchestratorId", location.getOrchestratorId());
        addFilter(filters, "infrastructureType", location.getInfrastructureType());
        count = alienDAO.count(Location.class, null, filters);
        if (count <= 1) {
            Map<Csar, List<Usage>> usages = locationArchiveIndexer.deleteArchives(location);
            if (MapUtils.isNotEmpty(usages)) {
                // TODO what to do when some archives were not deleted?
                log.warn("Some archives for location were not deletec! \n" + usages);
            }
        }
        // delete all location resources for the given location
        alienDAO.delete(LocationResourceTemplate.class, QueryBuilders.termQuery("locationId", id));
        // delete the location
        alienDAO.delete(Location.class, id);

        return true;
    }

    /**
     * Query for all locations given an orchestrator
     *
     * @param orchestratorId Id of the orchestrators for which to get locations.
     * @return An array that contains all locations for the given orchestrators.
     */
    public Location[] getOrchestratorLocations(String orchestratorId) {
        GetMultipleDataResult<Location> locations = alienDAO.search(Location.class, null,
                MapUtil.newHashMap(new String[] { "orchestratorId" }, new String[][] { new String[] { orchestratorId } }), Integer.MAX_VALUE);
        return locations.getData();
    }

    /**
     * Get all locations managed by all the provided orchestrators ids
     *
     * @param orchestratorIds
     * @return
     */
    public List<Location> getOrchestratorsLocations(Collection<String> orchestratorIds) {
        List<Location> locations = null;
        locations = alienDAO.customFindAll(Location.class, QueryBuilders.termsQuery("orchestratorId", orchestratorIds));
        return locations == null ? Lists.<Location> newArrayList() : locations;
    }

    private Map<String, String[]> getDeploymentFilterPerLocation(boolean activeOnly, String... locationIds) {
        Map<String, String[]> filters = Maps.newHashMap();

        filters.put("locationIds", locationIds);
        if (activeOnly) {
            filters.put("endDate", new String[] { "null" });
        }
        return filters;
    }

    private void addFilter(Map<String, String[]> filters, String property, String... values) {
        filters.put(property, values);
    }

}