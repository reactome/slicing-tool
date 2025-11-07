package org.gk.slicing;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.gk.database.EventCheckOutHandler;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.DiagramGKBWriter;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.PersistenceManager;
import org.gk.persistence.XMLFileAdaptor;
import org.gk.render.RenderablePathway;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.reactome.curation.util.CytoscapJSToRenderableDiagramConverter;

import com.fasterxml.jackson.core.JsonProcessingException;

/**
 * This class is responsible for converting Cytoscape.js JSON representations of biological pathways to the Reactome Diagram XML format.
 */
@SuppressWarnings("unchecked")
public class CytoscapJSToDiagramXMLConverter {
    private CytoscapJSToRenderableDiagramConverter converter = null;

    public CytoscapJSToDiagramXMLConverter() throws Exception {
        converter = new CytoscapJSToRenderableDiagramConverter();
        Map<String, Long> compartmentNameToIdMap = this.fetchCompartmentNameToIdMap();
        converter.setCompartmentNameToIdMap(compartmentNameToIdMap);
    }
    
    private Map<String, Long> fetchCompartmentNameToIdMap() throws Exception {
        // 500 should be enough
        InstanceList list = GraphDBInstanceManager.getInstance().listInstances(ReactomeJavaConstants.Compartment, 0, 500);
        Map<String, Long> nameToIdMap = new java.util.HashMap<>();
        for (SimpleInstance inst : list.getInstances()) {
            String displayName = inst.getDisplayName();
            Long dbId = inst.getDbId();
            nameToIdMap.put(displayName, dbId);
        }
        return nameToIdMap;
    }
    
    public String convert(File cytoscapeJSFile,
                         GKInstance pathwayInstance,
                         GKInstance diagramInstance) throws Exception {
        RenderablePathway diagram = convert(cytoscapeJSFile, diagramInstance.getDBID());
        
        // Convert diagram to xml
        DiagramGKBWriter writer = new DiagramGKBWriter();
        String diagramXML = writer.generateXMLString(diagram);
        return diagramXML;
    }

    public GKInstance convert(File cytoscapeJSFile,
                              Long pathwayDbId,
                              Long diagramDbId,
                              MySQLAdaptor dba) throws Exception {
        // Pull out the pathway instance from the database using the provided pathwayDbId
        GKInstance pathwayInstance = dba.fetchInstance(pathwayDbId);
        if (pathwayInstance == null) {
            throw new IllegalArgumentException("No pathway found with DB_ID: " + pathwayDbId);
        }
        // We also need to pull out the diagram instance from the graph database using diagramDbId
        GraphDBInstanceManager graphDbInstanceManager = GraphDBInstanceManager.getInstance();
        SimpleInstance graphDiagramInst = graphDbInstanceManager.getSimpleInstanceById(diagramDbId);
        GraphToRelInstanceConvertManager convertManager = GraphToRelInstanceConvertManager.getInstance();
        convertManager.setMySQLAdaptor(dba);
        GKInstance diagramInstance = convertManager.convertGraphToRelInstance(graphDiagramInst, 
                graphDbInstanceManager.getExtractedInstances());

        String diagramXML = this.convert(cytoscapeJSFile, pathwayInstance, diagramInstance);
//        System.out.println(diagramXML);
        diagramInstance.setAttributeValue(ReactomeJavaConstants.storedATXML, diagramXML);

        return diagramInstance;
    }

    private RenderablePathway convert(File cytoscapeJSFile, Long diagramDbId) throws IOException, JsonProcessingException, Exception {
        FileInputStream inputStream = new FileInputStream(cytoscapeJSFile);
        return this.converter.convert(inputStream, diagramDbId);
    }

    public void convertToRTPJFile(File cytoscapeJSFile, 
                                  Long pathwayDbId,
                                  Long diagramDbId,
                                  MySQLAdaptor dba,
                                  File outputRTPJFile) throws Exception {
        GKInstance diagram = convert(cytoscapeJSFile,
                pathwayDbId,
                diagramDbId,
                dba);
        XMLFileAdaptor fileAdaptor = new XMLFileAdaptor();

        PersistenceManager manager = PersistenceManager.getManager();
        manager.setActiveMySQLAdaptor(dba);
        manager.setActiveFileAdaptor(fileAdaptor);

//        List<GKInstance> toBeStored = new ArrayList<>();
//        toBeStored.add(diagram);
        List<GKInstance> pathways = diagram.getAttributeValuesList(ReactomeJavaConstants.representedPathway);
//        if (pathways != null) {
//            for (GKInstance pathway : pathways) {
//                toBeStored.add(pathway);
//            }
//        }
//        SynchronizationManager.getManager().checkOut(toBeStored, null);
        
        if (pathways != null) {
            EventCheckOutHandler handler = new EventCheckOutHandler();
            for (GKInstance pathway : pathways) {
                handler.checkOutEvent(pathway, fileAdaptor);
            }
        }
        
        fileAdaptor.save(outputRTPJFile.getAbsolutePath());
    }

    public static void main(String[] args) throws Exception {
        
        Long pathwayDbId = 9615710L;
        Long diagramDbId = 9631416L;
        
//        pathwayDbId = 9613829L; // Chaperone Mediated Autophagy
//        diagramDbId = 9626676L;
        
        MySQLAdaptor dba = new MySQLAdaptor("localhost", "test_graphdb_slice", "root", "macmysql01");
        String srcDir = "/Users/wug/Documents/web_curator_tool/diagram/cytoscape";
        String outputDir = "/Users/wug/temp";
        
        File cytoscapeJSFile = new File(srcDir, pathwayDbId + ".json");
        File outputXMLFile = new File(outputDir, pathwayDbId + ".rtpj");
        
        CytoscapJSToDiagramXMLConverter converter = new CytoscapJSToDiagramXMLConverter();
        converter.convertToRTPJFile(cytoscapeJSFile, pathwayDbId, diagramDbId, dba, outputXMLFile);
        // Here you would implement the logic to write the diagram to outputXMLFile
        System.out.println("Conversion completed. Output written to " + outputXMLFile.getAbsolutePath());
    }
    
}
