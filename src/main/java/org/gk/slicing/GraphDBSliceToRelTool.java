package org.gk.slicing;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;

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
    private String cytoscapeFolderName;

    public GraphDBSliceToRelTool() {
    }

    public String getCytoscapeFolderName() {
        return cytoscapeFolderName;
    }

    public void setCytoscapeFolderName(String cytoscapeFolderName) {
        this.cytoscapeFolderName = cytoscapeFolderName;
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
        logger.info("Reading top-level process IDs from file: " + processFileName);
        InputStream is = getClass().getClassLoader().getResourceAsStream(processFileName);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + processFileName);
        }
        this.topLevelIDs = readDbIds(is);
        return this.topLevelIDs;
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
            return this.speciesIDs;
        logger.info("Reading species IDs from file: " + speciesFileName);
        InputStream is = getClass().getClassLoader().getResourceAsStream(speciesFileName);
        if (is == null) {
            throw new IllegalArgumentException("Resource not found: " + speciesFileName);
        }
        this.speciesIDs = readDbIds(is);
        return this.speciesIDs;
    }

    @Override
    public void slice() throws Exception {
        if (!prepareTargetDatabase())
            throw new IllegalStateException("SlicingEngine.slice(): " +
                    "target database cannot be set up.");
        GraphToRelInstanceConvertManager conversionManager = GraphToRelInstanceConvertManager.getInstance();
        conversionManager.setMySQLAdaptor(targetDBA);

        GraphDBInstanceManager graphDBManager = GraphDBInstanceManager.getInstance();
        graphDBManager.setTopLevelIDs(getReleasedProcesses());
        graphDBManager.setSpeciesIds(readSpeciesIDs());
        graphDBManager.extractInstances();
        Map<Long, SimpleInstance> extractedInsts = graphDBManager.getSliceInstances();
        logger.info("Total extracted instances: " + extractedInsts.size());
        logger.info("Converting to GKInstances...");

        id2InstanceMap = convertToGKInstances(extractedInsts);
        resetEmptyDatetimeInInstanceEdits(id2InstanceMap);

        super.sliceMap = id2InstanceMap;

        // The following steps occur at the GKSchema level. Therefore, we can just
        // call the parent class methods.

        //        validateConditions();

        extractPathwayDiagrams();

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
        // fillReviewStatus() is not needed here: it is a relational-era step for assigning FiveStars to
        // Events that have never had a reviewStatus, which doesn't apply going forward.
        // copyReviewStatus() restores a higher star from the previous slice when a released pathway's
        // hasEvent hasn't structurally changed; hasEvent is already fully populated on every Pathway in
        // sliceMap by this point, so no source database or graph API call is needed for the comparison.
        copyReviewStatus();
        cleanUpPathwayFigures();
        //        // Add this step to remove NegativePrecedingEvent instances that don't have negativePrecedingEvent value
        // These NegativePrecedingEvent instances will also be removed from their referrers.
        cleanUpNegativePrecedingEvents();
        //        // This step has to be called just before dumpInstances() since the replacementInstance
        //        // slot in _Deleted will be checked against the sliceMap.
        dumpInstances();
        addFrontPage();
        addReleaseNumber();
        setStableIdReleased();
        //        handleRevisions();
        //        updateReviewStatusToSource(eventsWithReviewStatusUpdated);
    }

    private List<GKInstance> cleanUpRepresentedPathways(GKInstance pdInst) throws Exception {
        List<GKInstance> representedPathways = pdInst.getAttributeValuesList(ReactomeJavaConstants.representedPathway);
        if (representedPathways == null || representedPathways.isEmpty())
            return Collections.emptyList();
        for (Iterator<GKInstance> it = representedPathways.iterator(); it.hasNext(); ) {
            GKInstance representedPathway = it.next();
            if (sliceMap.containsKey(representedPathway.getDBID())) {
                continue;
            }
            it.remove();
        }
        return representedPathways;
    }

    /**
     * This overridden method is used to handle the context of PathwayDiagrams.
     */
    @Override
    protected void extractPathwayDiagrams() throws Exception {
        // Additional step to remove events that should not be released but in the diagrams for some reasons
        CytoscapJSToDiagramXMLConverter converter = new CytoscapJSToDiagramXMLConverter();
        List<Long> toBeRemoved = new ArrayList<>();
        for (Long dbId : sliceMap.keySet()) {
            GKInstance inst = sliceMap.get(dbId);
            if (!inst.getSchemClass().isa(ReactomeJavaConstants.PathwayDiagram))
                continue;
            List<GKInstance> representedPathways = cleanUpRepresentedPathways(inst);
            if (representedPathways.isEmpty()) {
                logger.info("Removing PathwayDiagram " + inst + " since its representedPathway is not in the slice.");
                toBeRemoved.add(dbId);
                continue; // No need to do anything for this pathway diagram. We will just remove it.
            }
            // Just in case. Technically there is no need.
            inst.setAttributeValue(ReactomeJavaConstants.representedPathway, representedPathways);
            GKInstance pathway = null;
            if (representedPathways.size() == 1)
                pathway = representedPathways.get(0);
            else {
                // Need to find the first one that is normal
                for (GKInstance p : representedPathways) {
                    if (p.getAttributeValue(ReactomeJavaConstants.disease) == null) {
                        pathway = p;
                        break;
                    }
                }
            }
            if (pathway == null) {
                logger.error("Cannot find a normal representedPathway for " + inst);
                continue;
            }
            // Check if there is any changed diagram in cytoscape folder
            boolean isHandled = false;
            if (cytoscapeFolderName != null) {
                String fileName = cytoscapeFolderName + "/" + pathway.getDBID() + ".json";
                java.io.File f = new java.io.File(fileName);
                if (f.exists()) {
                    try {
                        String xml = converter.convert(f, pathway, inst);
                        inst.setAttributeValue(ReactomeJavaConstants.storedATXML, xml);
                        isHandled = true;
                        logger.info("Updated diagramXML for " + inst + " from " + fileName);
                    }
                    catch(Exception e) {
                        logger.error("SlicingEngine.extractPathwayDiagrams(): " + e, e);
                    }
                }
            }
            if (!isHandled) {
                // There is nothing changed. We pull the existing diagramXML from the source database.
                GKInstance sourceInst = sourceDBA.fetchInstance(dbId);
                if (sourceInst != null) {
                    Object xml = sourceInst.getAttributeValue(ReactomeJavaConstants.storedATXML);
                    if (xml != null)
                        inst.setAttributeValue(ReactomeJavaConstants.storedATXML, xml);
                }
                else {
                    logger.error("Cannot find PathwayDiagram " + dbId + " in the source database!");
                }
            }
        }
        logger.info("PathwayDiagrams having representedPathways all not released: " + toBeRemoved.size());
        sliceMap.keySet().removeAll(toBeRemoved);
        // Remove events that are not in the slice, which means they are not released.
        // Additional step to remove events that should not be released but in the diagrams for some reasons
        // Also remove pathway diagrams having nothing drawn: This should not happen
        PathwayDiagramSlicingHelper diagramHelper = new PathwayDiagramSlicingHelper();
        toBeRemoved.clear();
        for (Long dbId : sliceMap.keySet()) {
            GKInstance inst = sliceMap.get(dbId);
            if (inst.getSchemClass().isa(ReactomeJavaConstants.PathwayDiagram)) {
                String atXML = (String) inst.getAttributeValue(ReactomeJavaConstants.storedATXML);
                if (atXML == null || atXML.length() == 0) {
                    logger.error("No diagram XML for PathwayDiagram. This instance will not be in slice: " + inst);
                    toBeRemoved.add(dbId);
                    continue;
                }
                diagramHelper.removeDoNotReleaseEvents(inst, targetDBA);
            }
        }
        logger.info("Empty PathwayDiagrams without text: " + toBeRemoved.size());
        sliceMap.keySet().removeAll(toBeRemoved);
        logger.info("Total extracted instances after extractPathwayDiagrams(): " + sliceMap.size());
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