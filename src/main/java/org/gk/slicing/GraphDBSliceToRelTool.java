package org.gk.slicing;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.reactome.curation.model.SimpleInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This customized slicing engine is designed to work with a GraphDB backend by leveraging GraphDBInstanceManager
 * an GraphToReactomeConverter for data retrieval and conversion.
 */
public class GraphDBSliceToRelTool extends ProjectBasedSlicingEngine {
    private static final Logger logger = LoggerFactory.getLogger(GraphDBSliceToRelTool.class);
    // Temporary storage for top-level IDs to be sliced for debugging and testing
    private Map<Long, GKInstance> id2InstanceMap;
    
    public GraphDBSliceToRelTool() {
    }
    
    public void setTargetDBA(MySQLAdaptor dba) {
        // Initialize the conversion manager with the target DBA
        GraphToRelInstanceConvertManager conversionManager = GraphToRelInstanceConvertManager.getInstance();
        conversionManager.setMySQLAdaptor(dba);
    }
    
    public void setTopLevelIDs(List<Long> topLevelIDs) {
        this.topLevelIDs = topLevelIDs;
    }
    
    public Map<Long, GKInstance> getExtractedInstances() {
        return id2InstanceMap;
    }
    
    private List<Long> getTopLevelIDs() throws Exception {
        if (topLevelIDs != null)
            return topLevelIDs;
        topLevelIDs = new ArrayList<>();
        InputStream is = getClass().getClassLoader().getResourceAsStream(processFileName);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + processFileName);
        }
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty())
                    continue;
                String[] tokens = line.split("\t");
                topLevelIDs.add(Long.parseLong(tokens[0].trim()));
            }
        }
        return topLevelIDs;
    }
    
    @Override
    public void slice() throws Exception {
        if (!prepareTargetDatabase())
            throw new IllegalStateException("SlicingEngine.slice(): " +
                    "target database cannot be set up.");
        GraphToRelInstanceConvertManager conversionManager = GraphToRelInstanceConvertManager.getInstance();
        conversionManager.setMySQLAdaptor(targetDBA);
        
        GraphDBInstanceManager graphDBManager = GraphDBInstanceManager.getInstance();
        graphDBManager.setTopLevelIDs(getTopLevelIDs());
        graphDBManager.extractInstances();
        Map<Long, SimpleInstance> extractedEvents = graphDBManager.getExtractedInstances();
        logger.info("Total extracted events: " + extractedEvents.size());
        logger.info("Converting to GKInstances...");
        
        id2InstanceMap = convertToGKInstances(extractedEvents);
        super.sliceMap = id2InstanceMap;
        dumpInstances();
        
//        validateConditions();
//        topLevelIDs = getReleasedProcesses();
//        speciesIDs = getSpeciesIDs();
//        if(!prepareTargetDatabase())
//            throw new IllegalStateException("SlicingEngine.slice(): " +
//                    "target database cannot be set up.");
//        eventMap = extractEvents();
//        extractReferences();
//        extractRegulations();
        // As of November, 2018, this class has been deleted
//        extractConcurrentEventSets();
        // This is not needed any more
//        extractReactionCoordinates();
//        extractSpecies();
//        extractPathwayDiagrams();
//        extractUpdateTrackerInstances();
//        extractReviewStatus();
//        PrintStream output = null;
//        if (logFileName != null)
//            output = new PrintStream(new FileOutputStream(logFileName));
//        else
//            output = System.err;
//        SlicingQualityAssay qa = new SlicingQualityAssay();
//        qa.setSliceMap(this.sliceMap);
//        qa.setSourceDBA(sourceDBA);
//        qa.validateExistence(output);
//        qa.validateEventsInHierarchy(topLevelIDs,
//                                     output);
//        qa.validateAttributes(output);
//        qa.validateStableIds(output); // Added a new check for StableIds on August 1, 2016
//        // Better call this method as the last QA to make sure the attributes have been checked.
//        qa.validateUpdateTrackers(output);
//        if (logFileName != null)
//            output.close(); // Close it if output is opened by the application
//        addReleaseStatus();
//        logger.info("Filling Attribute Values...");
//        // Need to fill values for Complex.includedLocation
//        fillIncludedLocationForComplex();
//        fillAttributeValuesForEntitySets();
//        List<GKInstance> eventsWithReviewStatusUpdated = fillReviewStatus();
//        // There is no need to get anything here
//        copyReviewStatus();
//        cleanUpPathwayFigures();
//        // Add this step to remove NegativePrecedingEvent instances that don't have negativePrecedingEvent value
//        // These NegativePrecedingEvent instances will also be removed from their referrers.
//        cleanUpNegativePrecedingEvents();
//        // This step has to be called just before dumpInstances() since the replacementInstance
//        // slot in _Deleted will be checked against the sliceMap.
//        handleDeletions();
//        dumpInstances();
//        addFrontPage();
//        addReleaseNumber();
//        setStableIdReleased();
//        handleRevisions();
//        updateReviewStatusToSource(eventsWithReviewStatusUpdated);
    }
    
    private Map<Long, GKInstance> convertToGKInstances(Map<Long, SimpleInstance> simpleInstances) throws Exception {
        Map<Long, GKInstance> id2InstanceMap = new HashMap<>();
        GraphToRelInstanceConvertManager conversionManager = GraphToRelInstanceConvertManager.getInstance();
        for (Long dbId : simpleInstances.keySet()) {
            SimpleInstance simpleInstance = simpleInstances.get(dbId);
            GKInstance gkInstance = conversionManager.convertGraphToRelInstance(simpleInstance);
            if (gkInstance != null)
                id2InstanceMap.put(dbId, gkInstance);
        }
        return id2InstanceMap;
    }
    

}