package org.gk.slicing;

import java.util.HashMap;
import java.util.Map;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.gk.schema.SchemaAttribute;
import org.gk.schema.SchemaClass;
import org.reactome.curation.model.SimpleInstance;

/**
 * This class is responsible for managing the conversion from SimpleInstance (graph database representation) to
 * GKInstance (relational database representation).
 */
public class GraphToRelInstanceConvertManager {
    
    private static GraphToRelInstanceConvertManager instance;
    // Cached converted GKInstances
    private Map<Long, GKInstance> gkInstanceCache;
    // Used to handle the relational database schema
    private MySQLAdaptor dba;
    
    private GraphToRelInstanceConvertManager() {
        this.gkInstanceCache = new HashMap<>();
    }

    public static GraphToRelInstanceConvertManager getInstance() {
        if (instance == null) {
            instance = new GraphToRelInstanceConvertManager();
        }
        return instance;
    }
    
    public void setMySQLAdaptor(MySQLAdaptor dba) {
        this.dba = dba;
    }
    
   
    /**
     * Convert a SimpleInstance to a GKInstance. 
     * @param simpleInstance The SimpleInstance to convert.
     * @return A new GKInstance mapped to the given SimpleInstance.
     */
    public GKInstance convertGraphToRelInstance(SimpleInstance simpleInstance) throws Exception {
        GKInstance gkInst = gkInstanceCache.get(simpleInstance.getDbId());
        if (gkInst != null)
            return gkInst;
        SchemaClass gkSchemaClass = dba.getSchema().getClassByName(simpleInstance.getSchemaClassName());
        // Create a new copy of GKInstance
        gkInst = new GKInstance();
        gkInst.setDBID(simpleInstance.getDbId());
        gkInst.setDisplayName(simpleInstance.getDisplayName());
        gkInst.setSchemaClass(gkSchemaClass);
        gkInst.setDbAdaptor(dba);
        gkInstanceCache.put(simpleInstance.getDbId(), gkInst); // Cache early to handle circular references
        // Map attributes
        if (simpleInstance.getAttributes() != null) {
            for (Map.Entry<String, Object> entry : simpleInstance.getAttributes().entrySet()) {
                String attrName = entry.getKey();
                Object attrValue = entry.getValue();
                if (attrValue == null || attrName.equals("stId") || attrName.equals("modified"))
                    continue;
                if (attrName.equals("displayName"))
                    attrName = ReactomeJavaConstants._displayName; 
                else if (attrName.equals("dbId")) {
                    attrName = ReactomeJavaConstants.DB_ID;
                    // Convert from Integer to Long
                    attrValue = Long.valueOf(attrValue.toString());
                }
                else if (attrName.equals("modifiedList"))
                    attrName = ReactomeJavaConstants.modified;
                // The following will throw exception if the attribute is not defined in the schema
                SchemaAttribute schemaAttr = gkSchemaClass.getAttribute(attrName);
                if (schemaAttr.isInstanceTypeAttribute()) {
                    if (attrValue instanceof SimpleInstance) {
                        // Recursively convert to GKInstance
                        SimpleInstance refInst = (SimpleInstance) attrValue;
                        GKInstance refGkInst = convertGraphToRelInstance(refInst);
                        gkInst.setAttributeValue(attrName, refGkInst);
                    }
                    else if (attrValue instanceof java.util.List) {
                        @SuppressWarnings("unchecked")
                        java.util.List<SimpleInstance> refValues = (java.util.List<SimpleInstance>) attrValue;
                        for (SimpleInstance refInst : refValues) {
                            GKInstance refGkInst = convertGraphToRelInstance(refInst);
                            gkInst.addAttributeValue(attrName, refGkInst);
                        }
                    }
                }
                else {
                    // Just copy the value
                    gkInst.setAttributeValue(attrName, attrValue);
                }
            }
        }
        gkInstanceCache.put(simpleInstance.getDbId(), gkInst);
        return gkInst;
    }
}

