package org.gk.slicing;

import java.util.HashMap;
import java.util.List;
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
    
    public Map<Long, GKInstance> getConvertedInstances() {
        return gkInstanceCache;
    }
    
    public void setMySQLAdaptor(MySQLAdaptor dba) {
        this.dba = dba;
    }
    
    private String getSchemaClassName(SimpleInstance simpleInstance) {
        String clsName = simpleInstance.getSchemaClassName();
        if (clsName.equals("TopLevelPathway"))
            clsName = ReactomeJavaConstants.Pathway;
        else if (clsName.equals("ReactionLikeEvent"))
            clsName = ReactomeJavaConstants.ReactionlikeEvent;
        else if (clsName.equals("UpdateTracker"))
            clsName = ReactomeJavaConstants._UpdateTracker;
        else if (clsName.equals("Deleted"))
            clsName = ReactomeJavaConstants._Deleted;
        else if (clsName.equals("DeletedInstance"))
            clsName = "_DeletedInstance";
        return clsName;
    }
    
    private String getAtttributeName(String attName, String schemaClassName) {
        if (attName.equals("displayName"))
            return ReactomeJavaConstants._displayName;
        else if (attName.equals("dbId"))
            return ReactomeJavaConstants.DB_ID;
        else if (attName.equals("modifiedList"))
            return ReactomeJavaConstants.modified;
        else if (attName.equals("doRelease"))
            return ReactomeJavaConstants._doRelease;
        else if (attName.equals("deletedInstanceDbId"))
            return ReactomeJavaConstants.deletedInstanceDB_ID;
        else if (attName.equals("replacementInstanceDbIds"))
            return "replacementInstanceDB_IDs";
        else if ((schemaClassName.equals(ReactomeJavaConstants.Compartment) || 
                 schemaClassName.startsWith("GO_")) 
                && attName.equals(ReactomeJavaConstants.identifier))
            return ReactomeJavaConstants.accession;
        return attName;
    }
    
   
    /**
     * Convert a SimpleInstance to a GKInstance. 
     * @param simpleInstance The SimpleInstance to convert.
     * @return A new GKInstance mapped to the given SimpleInstance.
     */
    public GKInstance convertGraphToRelInstance(SimpleInstance simpleInstance,
                                                Map<Long, SimpleInstance> id2graphInst) throws Exception {
        GKInstance gkInst = gkInstanceCache.get(simpleInstance.getDbId());
        if (gkInst != null)
            return gkInst;
        // We need to use the filled instance from the map to ensure all attributes are available
        SimpleInstance filledInstance = id2graphInst.get(simpleInstance.getDbId());
        if (filledInstance != null)
            simpleInstance = filledInstance; // Otherwise, use the given instance
        SchemaClass gkSchemaClass = dba.getSchema().getClassByName(getSchemaClassName(simpleInstance));
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
                // Need some conversion from the graph attribute name to the relational attribute name
                attrName = getAtttributeName(attrName, gkSchemaClass.getName());
                if (attrName.equals(ReactomeJavaConstants.DB_ID)) {
                    // Convert from Integer to Long
                    attrValue = Long.valueOf(attrValue.toString());
                }
                // The following will throw exception if the attribute is not defined in the schema
                SchemaAttribute schemaAttr = gkSchemaClass.getAttribute(attrName);
                if (schemaAttr.isInstanceTypeAttribute()) {
                    if (attrValue instanceof SimpleInstance) {
                        // Recursively convert to GKInstance
                        SimpleInstance refInst = (SimpleInstance) attrValue;
                        GKInstance refGkInst = convertGraphToRelInstance(refInst, id2graphInst);
                        gkInst.setAttributeValue(attrName, refGkInst);
                    }
                    else if (attrValue instanceof java.util.List) {
                        @SuppressWarnings("unchecked")
                        java.util.List<SimpleInstance> refValues = (java.util.List<SimpleInstance>) attrValue;
                        for (SimpleInstance refInst : refValues) {
                            GKInstance refGkInst = convertGraphToRelInstance(refInst, id2graphInst);
                            gkInst.addAttributeValue(attrName, refGkInst);
                        }
                    }
                }
                else {
                    // Just copy the value
                    // There is some inconsistency within ExternalOntologyTerm instances
                    if ((gkInst.getSchemClass().getName().equals(ReactomeJavaConstants.PsiMod) ||
                        gkInst.getSchemClass().getName().equals(ReactomeJavaConstants.SequenceOntology)) &&
                        (attrName.equals(ReactomeJavaConstants.name))) {
                        if (attrValue instanceof java.util.List) {
                            @SuppressWarnings("unchecked")
                            List<String> values = (List<String>) attrValue;
                            if (values.size() > 0)
                                gkInst.setAttributeValue(attrName, values.get(0));
                        }
                        else if (attrValue instanceof String)
                            gkInst.setAttributeValue(attrName, attrValue);
                    }
                    else
                        gkInst.setAttributeValue(attrName, attrValue);
                }
            }
        }
        return gkInst;
    }
}

