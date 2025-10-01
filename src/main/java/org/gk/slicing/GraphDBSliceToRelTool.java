package org.gk.slicing;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.model.InstanceDisplayNameGenerator;
import org.gk.model.ReactomeJavaConstants;
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
    
    /**
     * Use this method, instead of getReleasedProcesses() in the parent class, to read top-level IDs
     * to have a better control of what to be sliced (e.g. comment out some IDs).
     * @return
     * @throws Exception
     */
    @Override
    protected List<Long> getReleasedProcesses() throws Exception {
        if (topLevelIDs != null)
            return topLevelIDs;
        topLevelIDs = new ArrayList<>();
        logger.info("Reading top-level process IDs from file: " + processFileName);
        InputStream is = getClass().getClassLoader().getResourceAsStream(processFileName);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + processFileName);
        }
        return readDbIds(is);
    }

    private List<Long> readDbIds(InputStream is) throws IOException {
        List<Long> dbIds = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.trim().isEmpty())
                    continue;
                String[] tokens = line.split(" |\t");
                dbIds.add(Long.parseLong(tokens[0].trim()));
            }
        }
        logger.info("Total IDs read from file: " + dbIds.size());
        return dbIds;
    }
    
    private List<Long> readSpeciesIDs() throws Exception {
        if (speciesIDs != null)
            return null;
        speciesIDs = new ArrayList<>();
        logger.info("Reading species IDs from file: " + speciesFileName);
        InputStream is = getClass().getClassLoader().getResourceAsStream(speciesFileName);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + speciesFileName);
        }
        return readDbIds(is);
    }
    
    @Override
    public void slice() throws Exception {
        if (!prepareTargetDatabase())
            throw new IllegalStateException("SlicingEngine.slice(): " +
                    "target database cannot be set up.");
        //TODO: Make sure to validate the requirements: e.g. processFileName, speciesFileName, etc.
        GraphToRelInstanceConvertManager conversionManager = GraphToRelInstanceConvertManager.getInstance();
        conversionManager.setMySQLAdaptor(targetDBA);
        
        GraphDBInstanceManager graphDBManager = GraphDBInstanceManager.getInstance();
        graphDBManager.setTopLevelIDs(getReleasedProcesses());
        graphDBManager.setSpeciesIds(readSpeciesIDs());
        graphDBManager.extractInstances();
        Map<Long, SimpleInstance> extractedInsts = graphDBManager.getExtractedInstances();
        logger.info("Total extracted instances: " + extractedInsts.size());
        logger.info("Converting to GKInstances...");
        
        id2InstanceMap = convertToGKInstances(extractedInsts);
        resetEmptyDatetimeInInstanceEdits(id2InstanceMap);
        
        super.sliceMap = id2InstanceMap;
        
        // The following steps occur at the GKSchema level. Therefore, we can just
        // call the parent class methods.
        
//        validateConditions();

//        extractPathwayDiagrams();

        // The following QA steps are disabled since they are either not needed for GraphDB slicing
        // or they should be moved by the actual QA checks in other places.
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
        addReleaseStatus();
        logger.info("Filling Attribute Values...");
        // Need to fill values for Complex.includedLocation
        fillIncludedLocationForComplex();
        fillAttributeValuesForEntitySets();
//        List<GKInstance> eventsWithReviewStatusUpdated = fillReviewStatus();
//        // There is no need to get anything here
//        copyReviewStatus();
//        cleanUpPathwayFigures();
//        // Add this step to remove NegativePrecedingEvent instances that don't have negativePrecedingEvent value
        // These NegativePrecedingEvent instances will also be removed from their referrers.
        cleanUpNegativePrecedingEvents();
//        // This step has to be called just before dumpInstances() since the replacementInstance
//        // slot in _Deleted will be checked against the sliceMap.
        //TODO: This step needs to be published into GraphInstanceManager and make sure all needed instances have been
        // extracted.
//        handleDeletions();
        dumpInstances();
        addFrontPage();
        addReleaseNumber();
        setStableIdReleased();
//        handleRevisions();
//        updateReviewStatusToSource(eventsWithReviewStatusUpdated);
    }
    
    private void setStableIdReleased() throws Exception {
        if (!setReleasedInStableIdentifier)
            return; // There is no need to do this.
        // Make sure released attribute in StableIdentifiers are true in
        // the target database
        logger.info("set released = true for target database...");
        try {
            @SuppressWarnings("unchecked")
            Collection<GKInstance> c = targetDBA.fetchInstancesByClass(ReactomeJavaConstants.StableIdentifier);
            for (GKInstance inst : c) {
                Boolean released = (Boolean) inst.getAttributeValue(ReactomeJavaConstants.released);
                if (released == null || !released) {
                    inst.setAttributeValue(ReactomeJavaConstants.released,
                                           Boolean.TRUE);
                    targetDBA.updateInstanceAttribute(inst,
                                                      ReactomeJavaConstants.released);
                }
            }
        }
        catch(Exception e) {
            logger.error("SlicingEngine.setStableIdReleased(): " + e, e);
            return; // Don't need to continue
        }
        // Now set released = true in the source database
        GraphDBInstanceManager manager = GraphDBInstanceManager.getInstance();
        manager.setReleasedInStableIdentifiers();
        logger.info("Finished setting released = true for target database.");
    }
    
    private Map<Long, GKInstance> convertToGKInstances(Map<Long, SimpleInstance> simpleInstances) throws Exception {
        GraphToRelInstanceConvertManager conversionManager = GraphToRelInstanceConvertManager.getInstance();
        for (Long dbId : simpleInstances.keySet()) {
            SimpleInstance simpleInstance = simpleInstances.get(dbId);
            conversionManager.convertGraphToRelInstance(simpleInstance, simpleInstances);
        }
        return conversionManager.getConvertedInstances();
    }
    
    /**
     * The value '0000-00-00 00:00:00' is not a valid DATETIME under modern MySQL settings.
     * Since MySQL 5.7 (and in MySQL 8.x), strict mode is enabled by default, which forbids “zero” dates.
     * When your Java code tries to insert/update such a value, MySQL rejects it and JDBC raises a 
     * MysqlDataTruncation. This method is used to reset such values to 2000-01-01 00:00:01.
     */
    private void resetEmptyDatetimeInInstanceEdits(Map<Long, GKInstance> id2InstanceMap) throws Exception {
        logger.info("Resetting empty dateTime values in InstanceEdit instances...");
        for (Long dbId : id2InstanceMap.keySet()) {
            GKInstance instance = id2InstanceMap.get(dbId);
            if (!instance.getSchemClass().getName().equals(ReactomeJavaConstants.InstanceEdit))
                continue; // Skip InstanceEdit instances
            String dateTime = (String) instance.getAttributeValue(ReactomeJavaConstants.dateTime);
            if (dateTime != null && dateTime.equals("0000-00-00 00:00:00")) {
                instance.setAttributeValue(ReactomeJavaConstants.dateTime, "2000-01-01 00:00:01");
                // Need to update displayName as well
                InstanceDisplayNameGenerator.setDisplayName(instance);
            }
        }
        logger.info("Finished resetting empty dateTime values in InstanceEdit instances.");
    }
    

}