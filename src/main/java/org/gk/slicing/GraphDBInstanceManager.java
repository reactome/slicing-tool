package org.gk.slicing;

import java.util.HashMap;
import java.util.Map;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.SchemaAttribute;
import org.gk.schema.SchemaClass;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.user.model.User;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is responsible for managing instances of GraphDB via the curator-tool-ws RESTful API.
 */
public class GraphDBInstanceManager {
    // The following URLs should be externalized in a real application
    private static final String HOST_URL = "http://localhost:9090/api/"; // Base URL for the curator-tool-ws API
    private static final String AUTH_URL = HOST_URL + "authenticate"; // Endpoint to fetch JWT token
    private static final String GET_INST_URL = HOST_URL + "curation/findByDbId/"; // Endpoint from testJSONDeserization
    
    private static GraphDBInstanceManager instance;
    // Cache all loaded SimpleInstances
    private Map<Long, SimpleInstance> graphInstanceCache;
    private ObjectMapper objectMapper;
    private String jwtToken;

    private GraphDBInstanceManager() {
        this.jwtToken = this.fetchJwtToken("test", "password");
        this.objectMapper = new ObjectMapper();
        this.graphInstanceCache = new HashMap<>();
    }

    public static GraphDBInstanceManager getInstance() {
        if (instance == null) {
            instance = new GraphDBInstanceManager();
        }
        return instance;
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

