package org.reactome.slicingtool.test;

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

public class GraphDBSlicingTests {
    
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
        GraphDBSliceToRelTool slicingTool = new GraphDBSliceToRelTool();
        slicingTool.setTargetDBA(getDBA());
        // Used for testing
        List<Long> topLevelIDs = List.of(9612973L, 9909396L); // Autophay and Circadian clock
        slicingTool.setTopLevelIDs(topLevelIDs);
        Map<Long, GKInstance> events = slicingTool.extractEvents();
        for (Long dbId : events.keySet()) {
            GKInstance event = events.get(dbId);
            System.out.println("Event: " + event);
            for (Object obj : event.getSchemClass().getAttributes()) {
                SchemaAttribute attr = (SchemaAttribute) obj;
                System.out.println("  " + attr.getName() + ": " + event.getAttributeValue(attr.getName()));
            }
        }
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
        GKInstance gkInstance = conversionManager.convertGraphToRelInstance(instance);
        System.out.println("Converted GKInstance: " + gkInstance);
        for (Object obj : gkInstance.getSchemClass().getAttributes()) {
            SchemaAttribute attr = (SchemaAttribute) obj;
            System.out.println("  " + attr.getName() + ": " + gkInstance.getAttributeValue(attr.getName()));
        }
    }

}
