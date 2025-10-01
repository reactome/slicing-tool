package org.gk.slicing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.user.model.User;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is responsible for managing instances of GraphDB via the curator-tool-ws RESTful API.
 */
@SuppressWarnings("unchecked")
public class GraphDBInstanceManager {
    private static final Logger logger = LoggerFactory.getLogger(GraphDBInstanceManager.class);
    
    // The following URLs should be externalized in a real application
    private static final String HOST_URL = "http://localhost:9090/api/"; // Base URL for the curator-tool-ws API
    private static final String AUTH_URL = HOST_URL + "authenticate"; // Endpoint to fetch JWT token
    private static final String GET_INST_URL = HOST_URL + "curation/findByDbId/"; // Endpoint from testJSONDeserization
    private static final String EXIST_INST_URL = HOST_URL + "curation/existsByDbId/"; // Check if an instance exists by dbId
    private static final String UPDATE_INST_URL = HOST_URL + "curation/commit"; // Update an instance
    //@GetMapping("listInstances/{className}/{skip}/{limit}")
    private static final String LIST_INST_URL = HOST_URL+ "curation/listInstances/"; // List instances of a class
    private static final int PAGE_SIZE = 1000; // Number of instances to fetch per page
    
    private static GraphDBInstanceManager instance;
    // Cache all loaded SimpleInstances
    private Map<Long, SimpleInstance> graphInstanceCache;
    private ObjectMapper objectMapper;
    private String jwtToken;
    // Used to specify top-level instances for slicing
    private List<Long> topLevelIDs;
    // These species should be extracted even thought they are not used for
    // orthology inference
    private List<Long> speciesIds;
    // Tracked references pulling
    private Set<Long> refsProcessedIds;

    private GraphDBInstanceManager() {
        this.jwtToken = this.fetchJwtToken("test", "password");
        this.objectMapper = new ObjectMapper();
        this.graphInstanceCache = new HashMap<>();
        refsProcessedIds = new HashSet<>();
    }

    public static GraphDBInstanceManager getInstance() {
        if (instance == null) {
            instance = new GraphDBInstanceManager();
        }
        return instance;
    }
    
    public void setTopLevelIDs(List<Long> topLevelIDs) {
        this.topLevelIDs = topLevelIDs;
    }
    
    public void setSpeciesIds(List<Long> speciesIds) {
        this.speciesIds = speciesIds;
    }
    
    /**
     * Call this method to get all extracted instances after calling extractInstances().
     * @return
     */
    public Map<Long, SimpleInstance> getExtractedInstances() {
        return graphInstanceCache;
    }
    
    private void extractSpecies() {
        if (speciesIds == null || speciesIds.size() == 0)
            throw new IllegalStateException("Species IDs have not been set.");
        logger.info("Starting species extraction using GraphDBSlicingTool.");
        for (Long dbId : speciesIds) {
            logger.info("Processing species ID: " + dbId);
            // Fetch the SimpleInstance from GraphDB
            SimpleInstance species = getSimpleInstanceById(dbId);
            if (species == null) {
                logger.warn("Species with dbId " + dbId + " not found.");
                continue;
            }
            // We'd like to get all references for a species
            extractReferences(species);
            logger.info("Done: " + dbId);
        }
        logger.info("Species extraction completed: " + graphInstanceCache.size() + " species extracted.");
    }
    
    public void extractInstances() {
        extractEvents();
        extractSpecies();
        extractReviewStatuses();
        extractUpdateTracker();
        handleDeleted();
    }
    
    
    /**
     * Set released as true for all StableIdentifier instances pulled out from the graph database.
     */
    protected void setReleasedInStableIdentifiers() {
        logger.info("Setting released=true for all StableIdentifier instances...");
        for (SimpleInstance instance : graphInstanceCache.values()) {
            if (!instance.getSchemaClassName().equals(ReactomeJavaConstants.StableIdentifier))
                continue;
            // Maybe there is something wrong with the instance
            if (instance.getAttributes() == null) {
                logger.warn("StableIdentifier instance " + instance + " has no attributes.");
                continue;
            }
            Boolean released = (Boolean) instance.getAttributes().get(ReactomeJavaConstants.released);
            if (released != null && released)
                continue; // Already released
            instance.getAttributes().put(ReactomeJavaConstants.released, Boolean.TRUE);
            // Need to commit this change back to the graph database
            SimpleInstance updated = updateInstanceViaAPI(instance);
            if (updated == null) {
                logger.error("Failed to update StableIdentifier instance: " + instance);
            }
        }
        logger.info("Setting released=true for StableIdentifier instances completed.");
    }
    
    /**
     * The GraphDB version of handling deletions.
     */
    private void handleDeleted() {
        logger.info("Starting handling Deleted instances...");
        // List all Deletion instances
        int skip = 0;
        // Peek and get the total count
        InstanceList firstPage = listInstances("Deleted", skip, 1);
        int total = firstPage.getTotalCount();
        logger.info("Total Deleted instances to process: " + total);
        List<SimpleInstance> allDeleted = new ArrayList<>();
        while (skip < total) {
            logger.info("Processing Deleted instances: skip=" + skip + ", total=" + total);
            List<SimpleInstance> deleted = listInstances("Deleted", skip, PAGE_SIZE).getInstances();
            if (deleted == null || deleted.size() == 0) {
                break;
            }
            for (SimpleInstance del : deleted) {
                SimpleInstance instance = getSimpleInstanceById(del.getDbId());
                if (instance == null) {
                    logger.warn("Deletion instance with dbId " + del.getDbId() + " not found.");
                    continue; // Cannot find it!
                }
                allDeleted.add(instance);
            }
            skip += PAGE_SIZE;
//            break; // For testing only
        }
        logger.info("Total Deleted instances fetched: " + allDeleted.size());
        ensureReplacementInstances(allDeleted);
        // To keep the reference graph simple, we'd like to make sure all references for Deleted instances are extracted.
        total = 0;
        logger.info("Extracting one-hop references for all Deleted instances...");
        for (SimpleInstance deleted : allDeleted) {
            total++;
            extractOneHopReferences(deleted);
            if (total % 1000 == 0) {
                logger.info("Processed " + total + " Deleted instances for one-hop references.");
            }
        }
        logger.info("One-hop reference extraction for Deleted instances completed.");
        logger.info("Deletion handling completed. Total deletions processed: " + allDeleted.size());
    }
    
    /**
     * It is possible a replacementInstance may be deleted in an _Deleted instance. This method is used to figure
     * out if a replacementInstance can be found based on denormalized replacementInstanceDB_IDs in the same _Deleted
     * instance. This is a recursive search since the replacementInstance for the deleted replacementInstance may be
     * deleted again :-). 
     * @param deleted
     * @throws Exception
     */
    private void ensureReplacementInstances(Collection<SimpleInstance> allDeleted) {
        logger.info("Ensuring replacement instances for Deleted instances...");
        // Cache the deleted instance to replacement instances for quick search
        // A deleted instance may have multiple replacement instances, which makes the matter more complicated!
        Map<Integer, List<Integer>> deletedDBID2ReplacementDBIDs = new HashMap<>(); 
        for (SimpleInstance deleted : allDeleted) {
            List<Integer> deletedInstanceDB_IDList = (List<Integer>) deleted.getAttributes().get("deletedInstanceDbId");
            if (deletedInstanceDB_IDList == null || deletedInstanceDB_IDList.size() == 0)
                continue;
            List<Integer> replacementInstanceDB_IDList = (List<Integer>) deleted.getAttributes().get("replacementInstanceDBIds");
            if (replacementInstanceDB_IDList == null) {
                // An instance may get deleted without replacement. Register it so that there is no need to check the database
                replacementInstanceDB_IDList = Collections.EMPTY_LIST;
            }
            // Each deleted instance shares the same set of replacementInstances
            for (Integer dbId : deletedInstanceDB_IDList)
                deletedDBID2ReplacementDBIDs.put(dbId, replacementInstanceDB_IDList);
        }
        // Make sure replacementInstances are listed. If a replacementInstance is not listed, the code will try to find another
        // one recursively.
        for (SimpleInstance deleted : allDeleted) {
            // Make sure replacementInstances have what we want to display
            List<SimpleInstance> replacementInstancesList = (List<SimpleInstance>) deleted.getAttributes().get(ReactomeJavaConstants.replacementInstances);
            if (replacementInstancesList == null) {
                replacementInstancesList = new ArrayList<>();
                deleted.getAttributes().put(ReactomeJavaConstants.replacementInstances, replacementInstancesList);
            }
            // To control the size of the reference graph, we will make sure referred replacementInstances have been loaded already.
            // Remove those not loaded already.
            replacementInstancesList.removeIf(inst -> !graphInstanceCache.containsKey(inst.getDbId()));
            
            // In case a replacementInstance is deleted, we need to find another replacementInstance recursively.
            List<Integer> replacementInstanceDB_IDList = (List<Integer>) deleted.getAttributes().get("replacementInstanceDbIds");
            if (replacementInstanceDB_IDList == null || replacementInstanceDB_IDList.size() == 0)
                continue; // Cannot do anything
            Set<Integer> replacementDB_IDSet = new HashSet<>();
            for (Integer dbId : replacementInstanceDB_IDList) {
                ensureReplacementDBID(dbId,
                                      deletedDBID2ReplacementDBIDs,
                                      replacementDB_IDSet);
            }
            if (replacementDB_IDSet.size() == 0)
                continue; // Nothing can be done
            // For quick check
            Set<Integer> idSet = replacementInstancesList.stream().map(SimpleInstance::getDbId).map(Long::intValue).collect(Collectors.toSet());
            for (Integer dbId : replacementDB_IDSet) {
                if (idSet.contains(dbId))
                    continue; // Good. It is still there!
                // Use pull out instances only
                SimpleInstance inst = graphInstanceCache.get(dbId.longValue());
                if (inst == null) {
                    logger.warn(deleted + " has a replacement instance deleted. "
                            + "But cannot find its replacement (dbId is collected recursively) in the current slice: " + dbId);
                    continue;
                }
                if (!replacementInstanceDB_IDList.contains(dbId))
                    replacementInstanceDB_IDList.add(dbId);
                replacementInstancesList.add(inst); // Directly push it into the list. It should be placed into the value.
                logger.info(deleted + " has a replacement instance deleted but replaced with another: " + inst);
            }
        }
        logger.info("Replacement instance ensuring completed.");
    }
    
    /**
     * Make sure the replacementDBIDs are not deleted. Otherwise, try to find their replacement DB_IDs recursively.
     * @param replacementDBIDs
     */
    private void ensureReplacementDBID(Integer dbId,
                                       Map<Integer, List<Integer>> deletedDBID2ReplacementDBIDs,
                                       Set<Integer> existedDBIDs) {
        List<Integer> replacementDBIDs = deletedDBID2ReplacementDBIDs.get(dbId);
        if (replacementDBIDs == null) {
            existedDBIDs.add(dbId); // This dbId has not been deleted. This is good. Nothing needs to be done.
            return; 
        }
        // replacementDBIDs may be an empty List, which means it is deleted without replacement.
        for (Integer replacementDBID : replacementDBIDs)
            ensureReplacementDBID(replacementDBID, 
                                  deletedDBID2ReplacementDBIDs, 
                                  existedDBIDs);
    }
    
    private void extractUpdateTracker() {
        logger.info("Starting updateTracker extraction...");
        // List all UpdateTracker instances
        int skip = 0;
        // Peek and get the total count
        InstanceList firstPage = listInstances("UpdateTracker", skip, 1);
        int total = firstPage.getTotalCount();
        int instanceCount = 0;
        while (skip < total) {
            logger.info("Processing UpdateTracker instances: skip=" + skip + ", total=" + total);
            List<SimpleInstance> updateTrackers = listInstances("UpdateTracker", skip, PAGE_SIZE).getInstances();
            if (updateTrackers == null || updateTrackers.size() == 0) {
                break;
            }
            for (SimpleInstance ut : updateTrackers) {
                // UpdatedTracker displayName is formated like this: Update Tracker - [Complex:1604751] CTRB1 [extracellular region] - v44:[addName]
                // Therefore we can check if it should be processed based on updated id in display name
                String displayName = ut.getDisplayName();
                if (displayName == null || !displayName.contains("Update Tracker - ["))
                    continue; // Skip it    
                // Extract the dbId from displayName
                String idText = displayName.substring(displayName.indexOf('[') + 1, displayName.indexOf(']')).split(":")[1];
                Long id = null;
                if (idText.matches("\\d+")) {
                    id = Long.parseLong(idText);
                    if (!graphInstanceCache.containsKey(id))
                        continue; // Don't need to process it
                }
                SimpleInstance instance = getSimpleInstanceById(id);
                if (instance == null)
                    continue; // No updatedInstance attribute
                extractReferences(instance);
                instanceCount++;
            }
            skip += PAGE_SIZE;
        }
        logger.info("UpdateTracker extraction completed: " + instanceCount + " instances extracted.");
    }
    
    private void extractReviewStatuses() {
        logger.info("Starting reviewStatus extraction...");
        // List all ReviewStatus instances: Only 5 expected
        List<SimpleInstance> reviewStatuses = listInstances("ReviewStatus", 0, PAGE_SIZE).getInstances();
        if (reviewStatuses == null || reviewStatuses.size() == 0) {
            logger.error("No ReviewStatus instances found!"); 
            return;
        }
        for (SimpleInstance rs : reviewStatuses) {
            SimpleInstance instance = getSimpleInstanceById(rs.getDbId());
            extractReferences(instance);
        }
        logger.info("ReviewStatus extraction completed: " + reviewStatuses.size() + " instances extracted.");
    }
    
    private InstanceList listInstances(String className, int skip, int limit) {
        String url = LIST_INST_URL + className + "/" + skip + "/" + limit;
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Accept", "application/json");
            if (jwtToken != null) {
                request.setHeader("Authorization", "Bearer " + jwtToken);
            }
            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }
            String json = EntityUtils.toString(response.getEntity());
            InstanceList instanceList = objectMapper.readValue(json, InstanceList.class);
            return instanceList;
        } 
        catch (Exception e) {
            throw new RuntimeException("Error fetching instances of " + className + " from API", e);
        }
    }
    
    private void extractEvents() {
        if (topLevelIDs == null || topLevelIDs.size() == 0)
            throw new IllegalStateException("Top-level IDs have not been set.");
        logger.info("Starting event extraction using GraphDBSlicingTool.");
        for (Long dbId : topLevelIDs) {
            logger.info("Processing top-level ID: " + dbId);
            // Fetch the SimpleInstance from GraphDB
            extractHasEvent(dbId);
            logger.info("Done: " + dbId);
        }
        logger.info("Event extraction completed: " + graphInstanceCache.size() + " events extracted.");
        // Remove non-released events from the cache
        logger.info("Removing non-released events from the cache.");
        removeNotReleasedEvents();
        logger.info("Non-released events removed. Remaining events: " + graphInstanceCache.size());
        // Now extract all references for all non-event instances
        logger.info("Starting reference extraction...");
        Set<Long> eventIds = new HashSet<>(graphInstanceCache.keySet());
        int processedCount = 0;
        for (Long dbId : eventIds) {
//            logger.info("Processing event ID for references: " + dbId);
            SimpleInstance instance = graphInstanceCache.get(dbId);
            extractReferences(instance);
            processedCount++;
            if (processedCount % 100 == 0) {
                logger.info("Processed " + processedCount + " events for references.");
            }
        }
        logger.info("Reference extraction completed.");
    }
    
    
    /**
     * Extract the event branch starting from the given top-level event.
     * Note: hasMember is not used in the data model any more.
     * @param dbId for the Event object.
     */
    private void extractHasEvent(Long dbId) {
        if (graphInstanceCache.containsKey(dbId))
            return; // Already processed
        SimpleInstance event = getSimpleInstanceById(dbId);
        if (event == null)
            return;
        if (!isReleased(event))
            return; // Skip non-released events
        List<SimpleInstance> hasEventList = (List<SimpleInstance>) event.getAttributes().get(ReactomeJavaConstants.hasEvent);
        if (hasEventList == null || hasEventList.size() == 0)
            return;
        for (SimpleInstance subEvent : hasEventList) {
            extractHasEvent(subEvent.getDbId());
        }
    }
    
    private void removeNotReleasedEvents() {
        Set<Long> toBeRemoved = new HashSet<>();
        for (Long dbId : graphInstanceCache.keySet()) {
            SimpleInstance instance = graphInstanceCache.get(dbId);
            // At this stage, only events should be in the cache
            if (!isReleased(instance)) {
                toBeRemoved.add(dbId);
            }
        }
        graphInstanceCache.keySet().removeAll(toBeRemoved);
        // Clean up hasEvent references
        for (Long dbId : graphInstanceCache.keySet()) {
            SimpleInstance instance = graphInstanceCache.get(dbId);
            List<SimpleInstance> hasEventList = (List<SimpleInstance>) instance.getAttributes().get(ReactomeJavaConstants.hasEvent);
            if (hasEventList == null || hasEventList.size() == 0)
                continue;
            hasEventList.removeIf(e -> toBeRemoved.contains(e.getDbId()));
        }
    }
    
    private boolean isReleased(SimpleInstance instance) {
        Boolean doRelease = (Boolean) instance.getAttributes().get("doRelease");
        return (doRelease != null && doRelease);
    }
    
    private void extractNonEventReferences(SimpleInstance instance) {
        if (isEvent(instance) || refsProcessedIds.contains(instance.getDbId()))
            return; // Only process non-event instances
        // Need to get all attributes
        instance = getSimpleInstanceById(instance.getDbId());
        extractReferences(instance);
    }

    private void extractReferences(SimpleInstance instance) {
        if (instance.getAttributes() == null || instance.getAttributes().size() == 0)
            return; // Nothing more to do
//        logger.info("Extracting references for instance: " + instance);
        refsProcessedIds.add(instance.getDbId());
        for (String attName : instance.getAttributes().keySet()) {
            Object attValue = instance.getAttributes().get(attName);
            if (attValue == null)
                continue;
            if (attValue instanceof SimpleInstance) {
                extractNonEventReferences((SimpleInstance) attValue);
                continue;
            }
            if (attValue instanceof List) {
                List<Object> attValues = (List<Object>) attValue;
                if (attValues.size() == 0)
                    continue;
                // Peek at the first element to see if it is a reference
                if (!(attValues.get(0) instanceof SimpleInstance))
                    continue; // Not a list of references
                for (Object obj : attValues) {
                    extractNonEventReferences((SimpleInstance) obj);
                }
            }
        }
    }
    
    private void extractOneHopReferences(SimpleInstance instance) {
        if (instance.getAttributes() == null || instance.getAttributes().size() == 0)
            return; // Nothing more to do
        for (String attName : instance.getAttributes().keySet()) {
            Object attValue = instance.getAttributes().get(attName);
            if (attValue == null)
                continue;
            if (attValue instanceof SimpleInstance) {
                getSimpleInstanceById(((SimpleInstance) attValue).getDbId());
                continue;
            }
            if (attValue instanceof List) {
                List<Object> attValues = (List<Object>) attValue;
                if (attValues.size() == 0)
                    continue;
                // Peek at the first element to see if it is a reference
                if (!(attValues.get(0) instanceof SimpleInstance))
                    continue; // Not a list of references
                for (Object obj : attValues) {
                    getSimpleInstanceById(((SimpleInstance) obj).getDbId());
                }
            }
        }
    }
    
    private boolean isEvent(SimpleInstance instance) {
        Class<? extends DatabaseObject> cls = instance.getGraphModelClass();
        if (cls == null)
            return false;
        return (Event.class.isAssignableFrom(cls));
    }
    
    public SimpleInstance getSimpleInstanceById(Long dbId) {
        // Check cache first
        if (graphInstanceCache.containsKey(dbId)) {
            return graphInstanceCache.get(dbId);
        }
        // Fetch from RESTful API (pseudo-code, replace with actual API call)
        SimpleInstance simpleInstance = fetchSimpleInstanceFromAPI(dbId);
        if (simpleInstance != null) {
            graphInstanceCache.put(dbId, simpleInstance);
        }
        else throw new IllegalArgumentException("Instance with dbId " + dbId + " not found.");
        return simpleInstance;
    }   
    
    public void setJwtToken(String jwtToken) {
        this.jwtToken = jwtToken;
    }
    
    public String getJwtToken() {
        return jwtToken;
    }
    
    private boolean existsByDbId(Long dbId) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(EXIST_INST_URL + dbId);
            request.setHeader("Accept", "application/json");
            if (jwtToken != null) {
                request.setHeader("Authorization", "Bearer " + jwtToken);
            }
            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }
            Boolean exists = Boolean.valueOf(EntityUtils.toString(response.getEntity()));
            return exists;
        } 
        catch (Exception e) {
            throw new RuntimeException("Error checking existence of instance by dbId", e);
        }
    }

    private SimpleInstance fetchSimpleInstanceFromAPI(Long dbId) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(GET_INST_URL + dbId);
            request.setHeader("Accept", "application/json");
            if (jwtToken != null) {
                request.setHeader("Authorization", "Bearer " + jwtToken);
            }
            HttpResponse response = httpClient.execute(request);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }
            String json = EntityUtils.toString(response.getEntity());
            return objectMapper.readValue(json, SimpleInstance.class);
        } 
        catch (Exception e) {
            throw new RuntimeException("Error fetching SimpleInstance from API", e);
        }
    }
    
    private SimpleInstance updateInstanceViaAPI(SimpleInstance instance) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(UPDATE_INST_URL);
            post.setHeader("Content-Type", "application/json");
            if (jwtToken != null) {
                post.setHeader("Authorization", "Bearer " + jwtToken);
            }
            String jsonObj = objectMapper.writeValueAsString(instance);
            post.setEntity(new StringEntity(jsonObj));
            HttpResponse response = httpClient.execute(post);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }
            String json = EntityUtils.toString(response.getEntity());
            return objectMapper.readValue(json, SimpleInstance.class);
        } catch (Exception e) {
            throw new RuntimeException("Error updating instance via API", e);
        }
    }

    private String fetchJwtToken(String username, String password) {
        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(AUTH_URL);
            post.setHeader("Content-Type", "application/json");
            ObjectMapper mapper = new ObjectMapper();
            String jsonObj = mapper.writeValueAsString(new User(username, password));
            post.setEntity(new StringEntity(jsonObj));
            HttpResponse response = httpClient.execute(post);
            int statusCode = response.getStatusLine().getStatusCode();
            if (statusCode != 200) {
                throw new RuntimeException("Failed : HTTP error code : " + statusCode);
            }
            String jwt = EntityUtils.toString(response.getEntity());
            if (jwt.startsWith("\"") && jwt.endsWith("\"")) {
                jwt = jwt.substring(1, jwt.length() - 1);
            }
            this.jwtToken = jwt;
            return jwt;
        } catch (Exception e) {
            throw new RuntimeException("Error fetching JWT token from API", e);
        }
    }
    
}

