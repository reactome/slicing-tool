package org.gk.slicing;

import java.util.List;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This customized slicing engine is designed to work with a GraphDB backend by leveraging GraphDBInstanceManager
 * an GraphToReactomeConverter for data retrieval and conversion.
 */
public class GraphDBSliceToRelTool extends ProjectBasedSlicingEngine {
    private static final Logger logger = LoggerFactory.getLogger(GraphDBSliceToRelTool.class);
    
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
    
    @Override
    public Map<Long, GKInstance> extractEvents() throws Exception {
        logger.info("Starting event extraction using GraphDBSlicingTool.");
        // Initialize the GraphDBInstanceManager
        GraphDBInstanceManager graphDBManager = GraphDBInstanceManager.getInstance();
        Map<Long, GKInstance> extractedEvents = new java.util.HashMap<>();
        for (Long dbId : topLevelIDs) {
            logger.info("Processing top-level ID: " + dbId);
            // Fetch the SimpleInstance from GraphDB
            var simpleInstance = graphDBManager.getSimpleInstanceById(dbId);
            if (simpleInstance == null) {
                logger.warn("No SimpleInstance found for dbId: " + dbId);
                continue;
            }
        }
        logger.info("Event extraction completed.");
        return extractedEvents;
    }
    

}
