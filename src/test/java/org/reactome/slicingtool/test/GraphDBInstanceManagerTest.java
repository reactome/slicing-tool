package org.reactome.slicingtool.test;

import org.gk.model.GKInstance;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.SchemaAttribute;
import org.gk.slicing.GraphDBInstanceManager;
import org.junit.Test;
import org.reactome.curation.model.SimpleInstance;

public class GraphDBInstanceManagerTest {
    
    private GraphDBInstanceManager manager = GraphDBInstanceManager.getInstance();
    
    private MySQLAdaptor getDBA() throws Exception {
        MySQLAdaptor dba = new MySQLAdaptor("localhost",
                                            "gk_central_04142025", // As long as the schema is correct. The content does not matter.
                                            "root", 
                                            "macmysql01");
        return dba;
    }
    
    @Test
    public void testGetSimpleInstanceById() throws Exception {
        Long dbId = 141412L; // An EWAS
        // Implement test logic here
        SimpleInstance instance = manager.getSimpleInstanceById(dbId);
        System.out.println("Fetched instance: " + instance);
        System.out.println("Attributes: " + instance.getAttributes().size());
    }
    
    @Test
    public void testConvertToGKInstance() throws Exception {
        manager.setMySQLAdaptor(getDBA());
        
        Long dbId = 141412L; // An EWAS
        // Implement test logic here
        SimpleInstance instance = manager.getSimpleInstanceById(dbId);
        System.out.println("Fetched instance: " + instance);
        System.out.println("Attributes: " + instance.getAttributes().size());
        
        System.out.println("Converting to GKInstance...");
        GKInstance gkInstance = manager.convertGraphToRelInstance(instance);
        System.out.println("Converted GKInstance: " + gkInstance);
        for (Object obj : gkInstance.getSchemClass().getAttributes()) {
            SchemaAttribute attr = (SchemaAttribute) obj;
            System.out.println("  " + attr.getName() + ": " + gkInstance.getAttributeValue(attr.getName()));
        }
    }

}
