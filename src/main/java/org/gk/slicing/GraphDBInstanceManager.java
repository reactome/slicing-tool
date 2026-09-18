package org.gk.slicing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.gk.model.ReactomeJavaConstants;
import org.reactome.curation.CuratorToolWsApplication;
import org.reactome.curation.controller.CurationController;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.server.graph.domain.model.DatabaseObject;
import org.reactome.server.graph.domain.model.Event;
import org.reactome.server.graph.domain.model.Publication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This class is responsible for managing instances of GraphDB via the curator-tool-ws RESTful API.
 */
@SuppressWarnings("unchecked")
public class GraphDBInstanceManager {
    private static final Logger logger = LoggerFactory.getLogger(GraphDBInstanceManager.class);

//    // The following URLs should be externalized in a real application
//    private static final String HOST_URL = "http://localhost:9191/api/"; // Base URL for the curator-tool-ws API
//    private static final String AUTH_URL = HOST_URL + "auth/login"; // Endpoint to fetch JWT token
//    private static final String GET_INST_URL = HOST_URL + "curation/findByDbId/"; // Endpoint from testJSONDeserization
//    private static final String EXIST_INST_URL = HOST_URL + "curation/existsByDbId/"; // Check if an instance exists by dbId
//    private static final String UPDATE_INST_URL = HOST_URL + "curation/commit"; // Update an instance
//    //@GetMapping("listInstances/{className}/{skip}/{limit}")
//    private static final String LIST_INST_URL = HOST_URL+ "curation/listInstances/"; // List instances of a class

    private static final int PAGE_SIZE = 1000; // Number of instances to fetch per page
    private static GraphDBInstanceManager instance;
    // Cache all loaded SimpleInstances.
    private Map<Long, SimpleInstance> graphInstanceCache;
    // Instances in this map will be sliced into the slice database.
    // There are two stages of pulling: 1). Events in the hierarchy tree and their reference
    // 2). References of the events in the first stage.
    // Maintain this map to avoid to slice everything in graphInstanceCcahe into the database.
    // The graphInstanceCahce may have instances that should not be in the slice (e.g. event having
    // _doRelease = false
    private Map<Long, SimpleInstance> sliceInstanceCache;
    private ObjectMapper objectMapper;
    private String jwtToken;
    // Used to specify top-level instances for slicing
    private List<Long> topLevelIDs;
    // These species should be extracted even thought they are not used for
    // orthology inference
    private List<Long> speciesIds;
    // Tracked references pulling
    private Set<Long> refsProcessedIds;
    // Hooker to the graph database query using the controller in curator-tool-ws.
    private CurationController controller;
    private ConfigurableApplicationContext applicationContext;

    // A list of attributes introduced by the production server that should not be considered
    // Block orthologousEvent itself will not really block the value at the data model level at the graph database since the
    // actual value is pulled from inferredTo edges, which are created based on inferredFrom. InferredFrom is sliced into the
    // slice database.
    private Set<String> escapedAttributes = Stream.of("inferredTo", "orthologousEvent").collect(Collectors.toSet());

    public static void main(String[] args) {
        GraphDBInstanceManager manager = GraphDBInstanceManager.getInstance();
        try {
            Long dbId = 9612973L;
            SimpleInstance inst = manager.getSimpleInstanceById(dbId);
            System.out.println("Instance with dbId " + dbId + ": " + inst);
        } finally {
            manager.shutdown();
        }
        System.exit(0);
    }

    private GraphDBInstanceManager() {
        this.controller = initController();
        if (this.controller == null)
            throw new IllegalStateException("Cannot initialize CurationController from the application context.");
        this.objectMapper = new ObjectMapper();
        this.graphInstanceCache = new HashMap<>();
        this.sliceInstanceCache = new HashMap<>();
        refsProcessedIds = new HashSet<>();
    }

    private CurationController initController() {
        try {
            applicationContext = new SpringApplicationBuilder(CuratorToolWsApplication.class)
                .web(WebApplicationType.SERVLET)
                // Passed as a program argument (highest-priority property source) so it overrides
                // the server.port=9090 hardcoded in application.properties. Using .properties(...)
                // instead sets it as Spring Boot's lowest-priority "default property", which loses
                // to application.properties and lets the embedded server really bind port 9090.
                .run("--server.port=-1");
            return applicationContext.getBean(CurationController.class);
        }
        catch (Exception e) {
            logger.error("GraphDBInstanceManager.initController(): " + e.getMessage(), e);
        }
        return null;
    }

    private void shutdown() {
        if (applicationContext != null && applicationContext.isActive()) {
            applicationContext.close();
        }
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
    public Map<Long, SimpleInstance> getSliceInstances() {
        return this.sliceInstanceCache;
    }
    
    private void extractSpecies() {
        if (speciesIds == null || speciesIds.size() == 0)
            throw new IllegalStateException("Species IDs have not been set.");
        logger.info("Starting species extraction using GraphDBSlicingTool...");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
        int preSize = sliceInstanceCache.size();
        for (Long dbId : speciesIds) {
            logger.info("Processing species ID: " + dbId);
            // Fetch the SimpleInstance from GraphDB
            SimpleInstance species = getSimpleInstanceById(dbId);
            sliceInstanceCache.put(dbId, species);
            // We'd like to get all references for a species
            extractReferences(species);
            logger.info("Done: " + dbId);
        }
        int afterSize = sliceInstanceCache.size();
        logger.info("Species extraction completed: " + (afterSize - preSize) + " species-related instances extracted.");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
    }
    
    public void extractInstances() {
        extractEvents();
        extractSpecies();
        extractReviewStatuses();
        extractUpdateTracker();
        extractPathwayDiagrams();
        handleDeleted();
        convertAuthorNamesToAuthors();
    }
    
    private void extractPathwayDiagrams() {
        logger.info("Starting PathwayDiagram extraction...");
        logger.info("Total instances in sliceInstanceCache: " + sliceInstanceCache.size());
        // List all PathwayDiagram instances
        int skip = 0;
        // Peek and get the total count
        InstanceList firstPage = listInstances("PathwayDiagram", skip, 1);
        int total = firstPage.getTotalCount();
        logger.info("Total PathwayDiagram instances to process: " + total);
        int instanceCount = 0;
        while (skip < total) {
            logger.info("Processing PathwayDiagram instances: skip=" + skip + ", total=" + total);
            List<SimpleInstance> diagrams = listInstances("PathwayDiagram", skip, PAGE_SIZE).getInstances();
            if (diagrams == null || diagrams.size() == 0) {
                break;
            }
            for (SimpleInstance pd : diagrams) {
                SimpleInstance instance = getSimpleInstanceById(pd.getDbId());
                cleanUpRepresentedPathways(instance);
                if (!isPathwayDiagramNeeded(instance))
                    continue;
                sliceInstanceCache.put(pd.getDbId(), instance);
                cleanUpRenderedInstanceInPD(instance);
                extractOneHopReferences(instance);
                instanceCount++;
            }
            skip += PAGE_SIZE;
        }
        logger.info("PathwayDiagram extraction completed: " + instanceCount + " instances extracted.");
        logger.info("Total instances in sliceInstanceCache: " + sliceInstanceCache.size());
    }

    /**
     * Remove the renderedInstance attribute from a PathwayDiagram instance to
     * avoid pulling into the sliceMap. The renderedInstance is used to track
     * the rendered objects in WebBench, not for annotation. This is to avoid
     * pulling PEs that should not be pulled into the slice database.
     * @param pdInstance
     */
    private void cleanUpRenderedInstanceInPD(SimpleInstance pdInstance) {
        if (pdInstance == null || pdInstance.getAttributes() == null)
            return;
        if (pdInstance.getAttributes().containsKey("renderedInstance")) {
            pdInstance.getAttributes().remove("renderedInstance");
        }
    }

    private void cleanUpRepresentedPathways(SimpleInstance pdInstance) {
        List<SimpleInstance> pathwayList = (List<SimpleInstance>) pdInstance.getAttributes().get(ReactomeJavaConstants.representedPathway);
        if (pathwayList == null || pathwayList.isEmpty())
            return;
        for (Iterator<SimpleInstance> it = pathwayList.iterator(); it.hasNext(); ) {
            SimpleInstance pathwayInstance = it.next();
            if (sliceInstanceCache.containsKey(pathwayInstance.getDbId()))
                continue;
            it.remove(); // Remove it
        }
        // There is no need to reset the pathwayList.
    }

    private boolean isPathwayDiagramNeeded(SimpleInstance pdInstance) {
        List<SimpleInstance> pathwayList = (List<SimpleInstance>) pdInstance.getAttributes().get(ReactomeJavaConstants.representedPathway);
        if (pathwayList == null || pathwayList.isEmpty())
            return false;
        return true; // Since we have cleaned up representedPathway. All values should be good if any.
    }
    
    /**
     * Set released as true for all StableIdentifier instances pulled out from the graph database.
     */
    protected void setReleasedInStableIdentifiers() {
        logger.info("Setting released=true for all StableIdentifier instances...");
        for (SimpleInstance instance : sliceInstanceCache.values()) {
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
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
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
                sliceInstanceCache.put(instance.getDbId(), instance);
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
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
    }

    /**
     * As of the July 31, 2026 data model change, a Publication may record its authors as a list of
     * plain-text names in the authorName attribute instead of Person instances in the author attribute
     * (see LiteratureReferenceAttributeAutoFiller in curator-tool-ws). The relational data model still
     * requires author to be populated with Person instances, so this method converts each authorName
     * string back into a Person instance, reusing an already extracted Person instance when one with a
     * matching name is already in the slice.
     */
    private void convertAuthorNamesToAuthors() {
        logger.info("Starting converting Publication authorName into author Person instances...");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
        // Index all extracted Person instances by name so they can be reused instead of creating duplicates.
        Map<String, SimpleInstance> nameToPerson = new HashMap<>();
        long maxDbId = 0L;
        for (SimpleInstance instance : sliceInstanceCache.values()) {
            if (instance.getDbId() != null && instance.getDbId() > maxDbId)
                maxDbId = instance.getDbId();
            if (!ReactomeJavaConstants.Person.equals(instance.getSchemaClassName()))
                continue;
            String key = generatePersonNameKey(instance);
            if (key != null)
                nameToPerson.put(key, instance);
        }
        int convertedCount = 0;
        // Copy values since new Person instances will be added to sliceInstanceCache during the loop.
        // Need to have a new ArrayList to avoid ConcurrentModificationException since we may add new
        // instances to this cache.
        for (SimpleInstance instance : new ArrayList<>(sliceInstanceCache.values())) {
            if (!isPublication(instance) || instance.getAttributes() == null)
                continue;
            List<SimpleInstance> authors = (List<SimpleInstance>) instance.getAttributes().get(ReactomeJavaConstants.author);
            if (authors != null && authors.size() > 0) {
                continue; // Nothing to process
            }
            List<String> authorNames = (List<String>) instance.getAttributes().get("authorName");
            if (authorNames == null || authorNames.isEmpty())
                continue; // Cannot do anything
            // Try to get or create Person instances
            authors = new ArrayList<>(authorNames.size());
            for (String authorName : authorNames) {
                SimpleInstance person = nameToPerson.get(authorName);
                if (person == null) {
                    maxDbId++;
                    person = createPersonFromAuthorName(authorName, maxDbId);
                    nameToPerson.put(authorName, person);
                    sliceInstanceCache.put(person.getDbId(), person);
                }
                authors.add(person);
            }
            instance.getAttributes().put(ReactomeJavaConstants.author, authors);
            convertedCount++;
        }
        logger.info("Converted authorName to author for " + convertedCount + " Publication instances.");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
    }

    /**
     * Format a Person's name the same way the curator tool generates its display name:
     * "Surname, Firstname", falling back to "Surname, Initial", or just "Surname".
     */
    private String generatePersonNameKey(SimpleInstance person) {
        Map<String, Object> attributes = person.getAttributes();
        String surname = attributes == null ? null : (String) attributes.get(ReactomeJavaConstants.surname);
        if (surname == null || surname.trim().length() == 0)
            return person.getDisplayName(); // Fall back to the pre-computed display name for a shell instance.
        String given = (String) attributes.get(ReactomeJavaConstants.firstname);
        if (given == null || given.trim().length() == 0)
            given = (String) attributes.get(ReactomeJavaConstants.initial);
        if (given == null || given.trim().length() == 0)
            return surname.trim();
        // Following PubMed author format: e.g. Wang C,Weisman LS
        return surname.trim() + " " + given.trim();
    }

    /**
     * Create a new Person SimpleInstance from an authorName string formatted as "Surname, Firstname",
     * "Surname, Initial", or plain "Surname".
     */
    private SimpleInstance createPersonFromAuthorName(String authorName, long dbId) {
        SimpleInstance person = new SimpleInstance();
        person.setDbId(dbId);
        person.setSchemaClassName(ReactomeJavaConstants.Person);
        person.setDisplayName(authorName);
        Map<String, Object> attributes = new HashMap<>();
        // Split on whichever comes first, a comma or a space, e.g. "Wang, Chen", "Wang,Chen", "Wang Chen".
        int commaIndex = authorName.indexOf(',');
        int spaceIndex = authorName.indexOf(' ');
        int separatorIndex = commaIndex < 0 ? spaceIndex : spaceIndex < 0 ? commaIndex : Math.min(commaIndex, spaceIndex);
        String surname = (separatorIndex < 0 ? authorName : authorName.substring(0, separatorIndex)).trim();
        String given = separatorIndex < 0 ? null : authorName.substring(separatorIndex + 1).trim();
        attributes.put(ReactomeJavaConstants.surname, surname);
        if (given != null && given.length() > 0) {
            String initial = given.replaceAll("[.\\s]", "");
            // Treat an all upper-case given name as initials (e.g. "AN"); otherwise it is a firstname
            // (which may include a middle name), from which initials can still be derived.
            if (initial.length() > 0 && initial.length() <= 4 && initial.equals(initial.toUpperCase())) {
                attributes.put(ReactomeJavaConstants.initial, initial);
            } else {
                attributes.put(ReactomeJavaConstants.firstname, given);
                attributes.put(ReactomeJavaConstants.initial, generateInitialFromFirstName(given));
            }
        }
        person.setAttributes(attributes);
        // Reset the display name to match the curator tool's convention for Person instances.
        person.setDisplayName(generatePersonDisplayName(attributes));
        return person;
    }

    /**
     * Port of org.gk.model.InstanceDisplayNameGenerator's Person display name logic:
     * "Surname, Firstname", falling back to "Surname, Initial", or "Surname"/"Unknown".
     */
    private String generatePersonDisplayName(Map<String, Object> attributes) {
        StringBuilder buffer = new StringBuilder();
        String surname = (String) attributes.get(ReactomeJavaConstants.surname);
        if (surname == null || surname.length() == 0)
            buffer.append("Unknown");
        else
            buffer.append(surname);
        String firstname = (String) attributes.get(ReactomeJavaConstants.firstname);
        if (firstname != null && firstname.length() > 0) {
            buffer.append(", ");
            buffer.append(firstname);
        } else {
            String initial = (String) attributes.get(ReactomeJavaConstants.initial);
            if (initial != null && initial.length() > 0) {
                buffer.append(", ");
                buffer.append(initial);
            }
        }
        return buffer.toString();
    }

    private String generateInitialFromFirstName(String firstName) {
        StringBuilder initial = new StringBuilder();
        for (String part : firstName.split("\\s+")) {
            if (part.length() > 0)
                initial.append(Character.toUpperCase(part.charAt(0)));
        }
        return initial.toString();
    }

    /**
     * It is possible a replacementInstance may be deleted in an _Deleted instance. This method is used to figure
     * out if a replacementInstance can be found based on denormalized replacementInstanceDB_IDs in the same _Deleted
     * instance. This is a recursive search since the replacementInstance for the deleted replacementInstance may be
     * deleted again :-). 
     * @param allDeleted
     * @throws Exception
     */
    private void ensureReplacementInstances(Collection<SimpleInstance> allDeleted) {
        logger.info("Ensuring replacement instances for Deleted instances...");
        // Cache the deleted instance to replacement instances for quick search
        // A deleted instance may have multiple replacement instances, which makes the matter more complicated!
        Map<Long, List<Long>> deletedDBID2ReplacementDBIDs = new HashMap<>();
        for (SimpleInstance deleted : allDeleted) {
            List<Long> deletedInstanceDB_IDList = getDbIdList(deleted, "deletedInstanceDbId");
            if (deletedInstanceDB_IDList.isEmpty())
                continue;
            // An instance may get deleted without replacement. getDbIdList() returns an empty list for a
            // missing attribute, which registers it so that there is no need to check the database.
            List<Long> replacementInstanceDB_IDList = getDbIdList(deleted, "replacementInstanceDbIds");
            // Each deleted instance shares the same set of replacementInstances
            for (Long dbId : deletedInstanceDB_IDList)
                deletedDBID2ReplacementDBIDs.put(dbId, replacementInstanceDB_IDList);
        }
        // Make sure replacementInstances are listed. If a replacementInstance is not listed, the code will try to find another
        // one recursively.
        for (SimpleInstance deleted : allDeleted) {
            // Make sure replacementInstances have what we want to display. Work on a modifiable copy:
            // the list handed back by curator-tool-ws may be immutable.
            List<SimpleInstance> original = (List<SimpleInstance>) deleted.getAttributes().get(ReactomeJavaConstants.replacementInstances);
            List<SimpleInstance> replacementInstancesList = (original == null) ? new ArrayList<>() : new ArrayList<>(original);
            // To control the size of the reference graph, we will make sure referred replacementInstances have been loaded already.
            // Remove those not loaded already.
            replacementInstancesList.removeIf(inst -> !sliceInstanceCache.containsKey(inst.getDbId()));
            // Set it back now so the filtered list, and any addition made below, is visible in the instance
            deleted.setAttribute(ReactomeJavaConstants.replacementInstances, replacementInstancesList);

            // In case a replacementInstance is deleted, we need to find another replacementInstance recursively.
            List<Long> replacementInstanceDB_IDList = getDbIdList(deleted, "replacementInstanceDbIds");
            if (replacementInstanceDB_IDList.isEmpty())
                continue; // Cannot do anything
            Set<Long> replacementDB_IDSet = new HashSet<>();
            for (Long dbId : replacementInstanceDB_IDList) {
                ensureReplacementDBID(dbId,
                                      deletedDBID2ReplacementDBIDs,
                                      replacementDB_IDSet);
            }
            if (replacementDB_IDSet.isEmpty())
                continue; // Nothing can be done
            // For quick check
            Set<Long> idSet = replacementInstancesList.stream().map(SimpleInstance::getDbId).collect(Collectors.toSet());
            boolean dbIdsChanged = false;
            for (Long dbId : replacementDB_IDSet) {
                if (idSet.contains(dbId))
                    continue; // Good. It is still there!
                // Use pull out instances only
                SimpleInstance inst = sliceInstanceCache.get(dbId);
                if (inst == null) {
                    logger.warn(deleted + " has a replacement instance set by its dbId. "
                            + "But no instance with this dbId can be found in the slice: " + dbId);
                    continue;
                }
                if (!replacementInstanceDB_IDList.contains(dbId)) {
                    replacementInstanceDB_IDList.add(dbId);
                    dbIdsChanged = true;
                }
                replacementInstancesList.add(inst); // Directly push it into the list. It should be placed into the value.
                logger.info(deleted + " has a replacement instance deleted but replaced with another: " + inst);
            }
            if (dbIdsChanged) // getDbIdList() returned a copy, so the additions have to be written back
                deleted.setAttribute("replacementInstanceDbIds", replacementInstanceDB_IDList);
        }
        logger.info("Replacement instance ensuring completed.");
    }

    /**
     * Attribute values from curator-tool-ws may hold Integer or Long: Neo4j maps integral values to Long,
     * and generics erasure lets them through a List&lt;Integer&gt; cast (graph-core declares
     * List&lt;Integer&gt;, but the runtime list can contain Long). Normalize to Long and return a new
     * modifiable list, empty when the attribute is absent.
     */
    private List<Long> getDbIdList(SimpleInstance instance, String attName) {
        Object value = instance.getAttributes().get(attName);
        if (!(value instanceof List))
            return new ArrayList<>(); // Covers null and any unexpected type
        List<Long> rtn = new ArrayList<>();
        for (Object obj : (List<?>) value) {
            if (obj instanceof Number)
                rtn.add(((Number) obj).longValue());
        }
        return rtn;
    }

    /**
     * Make sure the replacementDBIDs are not deleted. Otherwise, try to find their replacement DB_IDs recursively.
     */
    private void ensureReplacementDBID(Long dbId,
                                       Map<Long, List<Long>> deletedDBID2ReplacementDBIDs,
                                       Set<Long> existedDBIDs) {
        List<Long> replacementDBIDs = deletedDBID2ReplacementDBIDs.get(dbId);
        if (replacementDBIDs == null) {
            existedDBIDs.add(dbId); // This dbId has not been deleted. This is good. Nothing needs to be done.
            return;
        }
        // replacementDBIDs may be an empty List, which means it is deleted without replacement.
        for (Long replacementDBID : replacementDBIDs)
            ensureReplacementDBID(replacementDBID,
                                  deletedDBID2ReplacementDBIDs,
                                  existedDBIDs);
    }

    private void extractUpdateTracker() {
        logger.info("Starting updateTracker extraction...");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
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
                    if (!sliceInstanceCache.containsKey(id))
                        continue; // Don't need to process it
                }
                SimpleInstance instance = getSimpleInstanceById(ut.getDbId());
                sliceInstanceCache.put(instance.getDbId(), instance);
                extractReferences(instance);
                instanceCount++;
            }
            skip += PAGE_SIZE;
        }
        logger.info("UpdateTracker extraction completed: " + instanceCount + " instances extracted.");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
    }
    
    private void extractReviewStatuses() {
        logger.info("Starting reviewStatus extraction...");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
        // List all ReviewStatus instances: Only 5 expected
        List<SimpleInstance> reviewStatuses = listInstances("ReviewStatus", 0, PAGE_SIZE).getInstances();
        if (reviewStatuses == null || reviewStatuses.size() == 0) {
            logger.error("No ReviewStatus instances found!"); 
            return;
        }
        for (SimpleInstance rs : reviewStatuses) {
            SimpleInstance instance = getSimpleInstanceById(rs.getDbId());
            sliceInstanceCache.put(rs.getDbId(), instance);
            extractReferences(instance);
        }
        logger.info("ReviewStatus extraction completed: " + reviewStatuses.size() + " instances extracted.");
        logger.info("Total instances in sliceInstanceCache: " + this.sliceInstanceCache.size());
    }
    
    protected InstanceList listInstances(String className, int skip, int limit) {
        InstanceList instanceList = this.controller.listInstances(className, skip, limit, Optional.empty());
        return instanceList;
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
        logger.info("Event extraction completed: " + sliceInstanceCache.size() + " events extracted.");
        // Remove non-released events from the cache
        logger.info("Removing non-released events from the cache.");
        removeNotReleasedEvents();
        logger.info("Non-released events removed. Remaining events: " + sliceInstanceCache.size());
//        checkSpecies(186860L);
        // Need to go through the event reference graph to pull in all events referring by.
        logger.info("Fetching all Events for reference graph closure...");
        extractEventTypeReferences(sliceInstanceCache);
        logger.info("Total instances after figuring out event references: " + sliceInstanceCache.size());
//        checkSpecies(186860L);
        // Now extract all references for all non-event instances
        logger.info("Starting reference extraction...");
        Set<Long> eventIds = new HashSet<>(sliceInstanceCache.keySet());
        int processedCount = 0;
        for (Long dbId : eventIds) {
//            logger.info("Processing event ID for references: " + dbId);
            SimpleInstance instance = sliceInstanceCache.get(dbId);
            extractReferences(instance);
            processedCount++;
            if (processedCount % 100 == 0) {
                logger.info("Processed " + processedCount + " events for references.");
            }
        }
        logger.info("Reference extraction completed. Total instances in sliceInstanceCahce: " + sliceInstanceCache.size());
        // Remove non-released events from attributes
        logger.info("Removing non-released events from attributes.");
        removeNoReleasedEventsInAttributes();
        logger.info("Total instances in sliceInstanceCache: " + sliceInstanceCache.size());
//        checkSpecies(186860L);
    }

    void checkSpecies(Long speciesId) {
        for (Long dbId : sliceInstanceCache.keySet()) {
            SimpleInstance instance = sliceInstanceCache.get(dbId);
            List<SimpleInstance> species = instance.getAttributes().get(ReactomeJavaConstants.species) instanceof List ? (List<SimpleInstance>) instance.getAttributes().get(ReactomeJavaConstants.species) : null;
            if (species == null || species.size() == 0) {
                continue;
            }
            for (SimpleInstance speciesInstance : species) {
                if (speciesInstance.getDbId().equals(speciesId)) {
                    System.out.println("Found species: " + speciesInstance.getDbId());
                }
            }
        }
    }

    private void removeNoReleasedEventsInAttributes() {
        for (Long dbId: sliceInstanceCache.keySet()) {
            SimpleInstance instance = sliceInstanceCache.get(dbId);
            Map<String, Object> attributes = instance.getAttributes();
            if (attributes == null || attributes.isEmpty()) {
                logger.warn("No attributes found for instance: " + dbId);
            }
            for (Iterator<String> it = attributes.keySet().iterator(); it.hasNext(); ) {
                String key = it.next();
                Object value = attributes.get(key);
                if (value instanceof SimpleInstance) {
                    SimpleInstance refInstance = (SimpleInstance) value;
                    // Here we should not check if it is released based on isReleased method since
                    // the values may be provided as shell instances
                    if (isEvent(refInstance) && !sliceInstanceCache.containsKey(refInstance.getDbId())) {
                        logger.info("Removing non-released reference: " + refInstance + " from instance: " + dbId + " in " + key);
                        it.remove();
                    }
                } else if (value instanceof List) {
                    // Just in case the list is not modifiable, create a new list and set it back to the attribute
                    List<Object> list = new ArrayList<>((List<Object>) value);
                    if (list.removeIf(item -> item instanceof SimpleInstance &&
                            isEvent((SimpleInstance) item) &&
                            !sliceInstanceCache.containsKey(((SimpleInstance) item).getDbId())))
                        instance.setAttribute(key, list);
                }
            }
        }
    }

    private void extractEventTypeReferences(Map<Long, SimpleInstance> eventMap) {
        Set<SimpleInstance> current = new HashSet<>(eventMap.values());
        Set<SimpleInstance> next = new HashSet<>();
        while (!current.isEmpty()) {
            for (SimpleInstance instance : current) {
                if (instance.getAttributes() == null)
                    continue;
                Set<String> toBeRemoved = new HashSet<>();
                for (String attribute : instance.getAttributes().keySet()) {
                    Object attValue = instance.getAttributes().get(attribute);
                    if (attValue instanceof SimpleInstance) {
                        SimpleInstance attInstance = (SimpleInstance) attValue;
                        attInstance = getSimpleInstanceById(attInstance.getDbId());
                        if (!isEvent(attInstance))
                            continue;
                        if (!isReleased(attInstance)) {
                            toBeRemoved.add(attribute);
                            continue; // Skip non-released events
                        }
                        addEventTypeReference(attInstance, eventMap, next);
                    }
                    else if (attValue instanceof List) {
                        List<?> values = (List<?>) attValue;
                        if (values.isEmpty() || !(values.get(0) instanceof SimpleInstance))
                            continue; // Not a list of references
                        for (Iterator<?> it = values.iterator(); it.hasNext(); ) {
                            SimpleInstance attInstance = (SimpleInstance) it.next();
                            attInstance = getSimpleInstanceById(attInstance.getDbId());
                            if (!isEvent(attInstance))
                                continue;
                            if (!isReleased(attInstance)) {
                                it.remove();
                                continue;
                            }
                            addEventTypeReference(attInstance, eventMap, next);
                        }
                        if (values.isEmpty()) {
                            toBeRemoved.add(attribute);
                        }
                    }
                }
                if (!toBeRemoved.isEmpty()) {
                    instance.getAttributes().keySet().removeAll(toBeRemoved);
                }
            }
            current = next;
            next = new HashSet<>();
        }
    }

    private void addEventTypeReference(SimpleInstance value,
                                       Map<Long, SimpleInstance> eventMap,
                                       Set<SimpleInstance> next) {
        if (eventMap.containsKey(value.getDbId()))
            return;
//        List<SimpleInstance> species = (List<SimpleInstance>) value.getAttributes().get(ReactomeJavaConstants.species);
//        if (species != null && !species.isEmpty()) {
//            for (SimpleInstance speciesInstance : species) {
//                if (speciesInstance.getDbId().equals(186860L)) {
//                    System.out.println("Found species: " + speciesInstance.getDbId());
//                }
//            }
//        }
        eventMap.put(value.getDbId(), value);
        next.add(value);
    }


    /**
     * Extract the event branch starting from the given top-level event.
     * Note: hasMember is not used in the data model any more.
     * @param dbId for the Event object.
     */
    private void extractHasEvent(Long dbId) {
        if (sliceInstanceCache.containsKey(dbId))
            return; // Already processed
        SimpleInstance event = getSimpleInstanceById(dbId);
        if (!isReleased(event))
            return; // Skip non-released events
        sliceInstanceCache.put(dbId, event);
        List<SimpleInstance> hasEventList = (List<SimpleInstance>) event.getAttributes().get(ReactomeJavaConstants.hasEvent);
        if (hasEventList == null || hasEventList.size() == 0)
            return;
        for (SimpleInstance subEvent : hasEventList) {
            extractHasEvent(subEvent.getDbId());
        }
    }
    
    private void removeNotReleasedEvents() {
        Set<Long> toBeRemoved = new HashSet<>();
        for (Long dbId : sliceInstanceCache.keySet()) {
            SimpleInstance instance = sliceInstanceCache.get(dbId);
            // At this stage, only events should be in the cache
            if (!isReleased(instance)) {
                toBeRemoved.add(dbId);
            }
        }
        sliceInstanceCache.keySet().removeAll(toBeRemoved);
        // Clean up hasEvent references
        for (Long dbId : sliceInstanceCache.keySet()) {
            SimpleInstance instance = sliceInstanceCache.get(dbId);
            List<SimpleInstance> hasEventList = (List<SimpleInstance>) instance.getAttributes().get(ReactomeJavaConstants.hasEvent);
            if (hasEventList == null || hasEventList.size() == 0)
                continue;
            hasEventList.removeIf(e -> toBeRemoved.contains(e.getDbId()));
        }
    }
    
    private boolean isReleased(SimpleInstance instance) {
        if (instance.getAttributes() == null || instance.getAttributes().size() == 0)
            return false; // Must not be set
        Boolean doRelease = (Boolean) instance.getAttributes().get("doRelease");
        return (doRelease != null && doRelease);
    }
    
    private void extractNonEventReferences(SimpleInstance instance) {
        if (isEvent(instance) || refsProcessedIds.contains(instance.getDbId()))
            return; // Only process non-event instances
        // Need to get all attributes
        instance = getSimpleInstanceById(instance.getDbId());
        sliceInstanceCache.put(instance.getDbId(), instance);
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
                SimpleInstance inst = getSimpleInstanceById(((SimpleInstance) attValue).getDbId());
                sliceInstanceCache.put(inst.getDbId(), inst);
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
                    SimpleInstance inst = getSimpleInstanceById(((SimpleInstance) obj).getDbId());
                    sliceInstanceCache.put(inst.getDbId(), inst);
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

    /**
     * Publication is an abstract schema class (e.g. LiteratureReference, Book are concrete subclasses),
     * so schemaClassName will never literally be "Publication" and must be checked via the class hierarchy.
     */
    private boolean isPublication(SimpleInstance instance) {
        Class<? extends DatabaseObject> cls = instance.getGraphModelClass();
        if (cls == null)
            return false;
        return (Publication.class.isAssignableFrom(cls));
    }
    
    public SimpleInstance getSimpleInstanceById(Long dbId) {
        // Check cache first
        if (graphInstanceCache.containsKey(dbId)) {
            return graphInstanceCache.get(dbId);
        }
        // Fetch from RESTful API (pseudo-code, replace with actual API call)
        SimpleInstance simpleInstance = fetchSimpleInstanceFromAPI(dbId);
        if (simpleInstance != null) {
            // Clean up the instance at the top
            if (simpleInstance.getAttributes() != null) {
                simpleInstance.getAttributes().keySet().removeAll(escapedAttributes);
            }
            graphInstanceCache.put(dbId, simpleInstance);
        }
        else throw new IllegalArgumentException("Instance with dbId " + dbId + " not found.");
        return simpleInstance;
    }
    
    private boolean existsByDbId(Long dbId) {
        return this.controller.existsByDbId(dbId);
    }

    private SimpleInstance fetchSimpleInstanceFromAPI(Long dbId) {
        return this.controller.findByDdIdInInstance(dbId);
    }
    
    private SimpleInstance updateInstanceViaAPI(SimpleInstance instance) {
        return this.controller.commit(instance);
    }
}

