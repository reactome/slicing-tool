package org.reactome.slicingtool.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.SchemaAttribute;
import org.gk.slicing.GraphDBInstanceManager;
import org.gk.slicing.GraphDBSliceToRelTool;
import org.gk.slicing.GraphToRelInstanceConvertManager;
import org.junit.Test;
import org.reactome.curation.model.SimpleInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GraphDBSlicingTests {
    private static final Logger logger = LoggerFactory.getLogger(GraphDBSlicingTests.class);
    
    private GraphDBInstanceManager graphDbInstaneManager = GraphDBInstanceManager.getInstance();
    private GraphToRelInstanceConvertManager conversionManager = GraphToRelInstanceConvertManager.getInstance();
    
    private MySQLAdaptor getDBA() throws Exception {
        MySQLAdaptor dba = new MySQLAdaptor("localhost",
                                            "gk_central_04142025", // As long as the schema is correct. The content does not matter.
                                            "root", 
                                            "macmysql01");
        return dba;
    }
    
    
    @Test
    public void testGraphDBSlicingTool() throws Exception {
        logger.info("Testing GraphDBSliceToRelTool...");
        long start = System.currentTimeMillis();
        GraphDBSliceToRelTool slicingTool = new GraphDBSliceToRelTool();
        slicingTool.setTargetDBA(getDBA());
        // Used for testing
        List<Long> topLevelIDs = List.of(9612973L, 9909396L); // Autophay and Circadian clock
        slicingTool.setTopLevelIDs(topLevelIDs);
        slicingTool.slice();
        Map<Long, GKInstance> events = slicingTool.getExtractedInstances();
        for (Long dbId : events.keySet()) {
            GKInstance event = events.get(dbId);
            logger.info("Event: " + event);
            for (Object obj : event.getSchemClass().getAttributes()) {
                SchemaAttribute attr = (SchemaAttribute) obj;
                List<?> values = event.getAttributeValuesList(attr.getName());
                if (values == null || values.size() == 0)
                    continue;
                List<String> strValues = values.stream().map(v -> v.toString()).collect(java.util.stream.Collectors.toList());
                logger.info("  " + attr.getName() + ": " + String.join("; ", strValues));
            }
            break; // Just print one
        }
        logger.info("Total extracted instances: " + events.size());
        long end = System.currentTimeMillis();
        logger.info("Total time: " + (end - start)/1000 + " seconds.");
    }
    
    @Test
    public void testGetSimpleInstanceById() throws Exception {
        Long dbId = 141412L; // An EWAS
        // Implement test logic here
        SimpleInstance instance = graphDbInstaneManager.getSimpleInstanceById(dbId);
        System.out.println("Fetched instance: " + instance);
        System.out.println("Attributes: " + instance.getAttributes().size());
    }
    
    @Test
    public void testConvertToGKInstance() throws Exception {
        conversionManager.setMySQLAdaptor(getDBA());
        
        Long dbId = 141412L; // An EWAS
        // Implement test logic here
        SimpleInstance instance = graphDbInstaneManager.getSimpleInstanceById(dbId);
        System.out.println("Fetched instance: " + instance);
        System.out.println("Attributes: " + instance.getAttributes().size());
        
        System.out.println("Converting to GKInstance...");
        GKInstance gkInstance = conversionManager.convertGraphToRelInstance(instance, new HashMap<>());
        System.out.println("Converted GKInstance: " + gkInstance);
        for (Object obj : gkInstance.getSchemClass().getAttributes()) {
            SchemaAttribute attr = (SchemaAttribute) obj;
            System.out.println("  " + attr.getName() + ": " + gkInstance.getAttributeValue(attr.getName()));
        }
    }

}
