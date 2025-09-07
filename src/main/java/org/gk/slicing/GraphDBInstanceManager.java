package org.gk.slicing;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.gk.model.ReactomeJavaConstants;
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
    
    private static GraphDBInstanceManager instance;
    // Cache all loaded SimpleInstances
    private Map<Long, SimpleInstance> graphInstanceCache;
    private ObjectMapper objectMapper;
    private String jwtToken;
    // Used to specify top-level instances for slicing
    private List<Long> topLevelIDs;
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
    
    /**
     * Call this method to get all extracted instances after calling extractInstances().
     * @return
     */
    public Map<Long, SimpleInstance> getExtractedInstances() {
        return graphInstanceCache;
    }
    
    public void extractInstances() {
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

