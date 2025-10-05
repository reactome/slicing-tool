package org.gk.slicing;

import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.gk.database.SynchronizationManager;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.DiagramGKBWriter;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.PersistenceManager;
import org.gk.persistence.XMLFileAdaptor;
import org.gk.render.Node;
import org.gk.render.ProcessNode;
import org.gk.render.Renderable;
import org.gk.render.RenderableChemical;
import org.gk.render.RenderableCompartment;
import org.gk.render.RenderableComplex;
import org.gk.render.RenderableEntitySet;
import org.gk.render.RenderablePathway;
import org.gk.render.RenderableProtein;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is responsible for converting Cytoscape.js JSON representations of biological pathways to the Reactome Diagram XML format.
 */
public class CytoscapJSToDiagramXMLConverter {
    private static final Logger logger = LoggerFactory.getLogger(CytoscapJSToDiagramXMLConverter.class);
    
    public CytoscapJSToDiagramXMLConverter() {
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
        
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(cytoscapeJSFile);
        RenderablePathway diagram = new RenderablePathway();
        diagram.setReactomeDiagramId(diagramDbId);
        convert(root, diagram, dba);
        
        // Convert diagram to xml
        DiagramGKBWriter writer = new DiagramGKBWriter();
        String diagramXML = writer.generateXMLString(diagram);
        diagramInstance.setAttributeValue(ReactomeJavaConstants.storedATXML, diagramXML);
        
        return diagramInstance;
    }
    
    private void convert(JsonNode cytoscapeNode, RenderablePathway diagram, MySQLAdaptor dba) throws Exception {
        // Implement the logic to convert Cytoscape.js JSON nodes to RenderablePathway elements
        // Elements block has nodes + edges
        JsonNode elements = cytoscapeNode.path("elements");

        /* ---- Traverse Nodes ---- */
        JsonNode nodes = elements.path("nodes");
        if (nodes.isArray()) {
            // Handle compartments in the second pass.
            Map<Integer, List<JsonNode>> id2Compartment = new java.util.HashMap<>();
            for (JsonNode node : nodes) {
                JsonNode data = node.path("data");
                String id = data.path("id").asText();
                String reactomeId = data.path("reactomeId").asText("");
                String classes = node.path("classes").asText("");
                
                Renderable renderable = null;
                if (classes.contains("Protein")) 
                    renderable = new RenderableProtein();
                else if (classes.contains("EntitySet")) 
                    renderable = new RenderableEntitySet();
                else if (classes.contains("Molecule")) 
                    renderable = new RenderableChemical();
                else if (classes.contains("Complex"))
                    renderable = new RenderableComplex();
                else if (classes.contains("Pathway"))
                    renderable = new ProcessNode();
                else if (classes.contains("Compartment")) {
                    Integer compartmentId = Integer.parseInt(id.split("-")[0]);
                    List<JsonNode> list = id2Compartment.get(compartmentId);
                    if (list == null) {
                        list = new ArrayList<>();
                        id2Compartment.put(compartmentId, list);
                    }
                    list.add(node);
                    continue;
                }
                if (renderable == null) {
                    logger.warn("Unsupported node class: " + classes + " for node id: " + id);
                    continue;
                }
                
                renderable.setReactomeId(Long.parseLong(reactomeId));
                renderable.setID(Integer.parseInt(id));
                String label = data.path("label").asText("");
                String type = data.path("type").asText("");

                double x = node.path("position").path("x").asDouble();
                double y = node.path("position").path("y").asDouble();
                renderable.setPosition((int)x, (int)y);
                // Get width and height if available
                double width = data.path("width").asDouble();
                double height = data.path("height").asDouble();
                
                Rectangle bounds = new Rectangle((int)(x - width / 2), (int) (y - height / 2), (int)width, (int)height);
                ((Node)renderable).setBounds(bounds);

                logger.debug("Node: id=" + id + ", label=" + label + 
                                   ", type=" + type + ", pos=(" + x + "," + y + ")");

                diagram.addComponent(renderable);
            }
            handleCompartments(id2Compartment, diagram, cytoscapeNode);
        }

        /* ---- Traverse Edges ---- */
        JsonNode edges = elements.path("edges");
        if (edges.isArray()) {
            for (JsonNode edge : edges) {
                JsonNode data = edge.path("data");
                String id = data.path("id").asText();
                String source = data.path("source").asText();
                String target = data.path("target").asText();
                String interaction = data.path("interaction").asText("");

                System.out.println("Edge: id=" + id + ", source=" + source + 
                                   ", target=" + target + ", interaction=" + interaction);

                // Print any other edge data fields
                Iterator<Map.Entry<String, JsonNode>> fields = data.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    System.out.println("   data." + entry.getKey() + " = " + entry.getValue());
                }
            }
        }
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
    
    private void handleCompartments(Map<Integer, List<JsonNode>> id2Compartment,
                                    RenderablePathway diagram,
                                    JsonNode cytoscapeNode) throws Exception {
        double zoom = cytoscapeNode.path("zoom").asDouble(1.0);
        double panX = cytoscapeNode.path("pan").path("x").asDouble(0.0);
        double panY = cytoscapeNode.path("pan").path("y").asDouble(0.0);
        Map<String, Long> nameToIdMap = fetchCompartmentNameToIdMap();
        for (Integer compartmentId : id2Compartment.keySet()) {
            List<JsonNode> nodes = id2Compartment.get(compartmentId);
            // Figure out which is inner and which is outer
            if (nodes.size() != 2) {
                logger.error("Compartment with id: " + compartmentId + " does not have exactly two nodes: " + nodes.size());
                continue;
            }
            JsonNode innerNode = null;
            JsonNode outerNode = null;
            for (JsonNode node : nodes) {
                String nodeId = node.path("data").path("id").asText();
                if (nodeId.contains("-inner"))
                    innerNode = node;
                else
                    outerNode = node;
            }
            // Handle outer first
            JsonNode data = outerNode.path("data");
            String displayName = data.path("displayName").asText();
            Long compartmentDbId = nameToIdMap.get(displayName);
            if (compartmentDbId == null) {
                logger.error("Skipping compartment with id: " + compartmentId + " and displayName: " + displayName);
                continue;
            }
            
            RenderableCompartment compartment = new RenderableCompartment();
            compartment.setReactomeId(compartmentDbId);
            compartment.setID(compartmentId);
            
            double x = outerNode.path("position").path("x").asDouble();
            double y = outerNode.path("position").path("y").asDouble();
            compartment.setPosition((int)x, (int)y);
            // Get width and height if available
            double width = data.path("width").asDouble();
            double height = data.path("height").asDouble();
            
            Rectangle bounds = new Rectangle((int)(x - width / 2), (int) (y - height / 2), (int)width, (int)height);
            ((Node)compartment).setBounds(bounds);
            
            // Also need to set the label location
            // textX and textY are offsets to the bottom-right corner of the compartment
            // textX and textY are negative values and should not be scaled by zoom and pan
            double labelX = x + width / 2 + data.path("textX").asDouble();
            double labelY = y + height / 2 + data.path("textY").asDouble();
            compartment.setTextPosition((int)(labelX), (int)(labelY));
            
            // Calculate insets based on inner node
            double innerX = innerNode.path("position").path("x").asDouble();
            double innerY = innerNode.path("position").path("y").asDouble();
            double innerWidth = innerNode.path("data").path("width").asDouble();
            double innerHeight = innerNode.path("data").path("height").asDouble();
            Rectangle innerBounds = new Rectangle((int)(innerX - innerWidth / 2), (int) (innerY - innerHeight / 2), (int)innerWidth, (int)innerHeight);
            compartment.setInsets(innerBounds);

            diagram.addComponent(compartment);
        }
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
        
        List<GKInstance> toBeStored = new ArrayList<>();
        toBeStored.add(diagram);
        List<GKInstance> pathways = diagram.getAttributeValuesList(ReactomeJavaConstants.representedPathway);
        if (pathways != null)
            toBeStored.addAll(pathways);
        SynchronizationManager.getManager().checkOut(toBeStored, null);
        
        fileAdaptor.save(outputRTPJFile.getAbsolutePath());
    }
    
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: java CytoscapJSToDiagramXMLConverter <cytoscapeJSFile> <outputXMLFile>");
            System.exit(1);
        }
        File cytoscapeJSFile = new File(args[0]);
        File outputXMLFile = new File(args[1]);
        
        Long pathwayDbId = 9615710L;
        Long diagramDbId = 9631416L;
        MySQLAdaptor dba = new MySQLAdaptor("localhost", "test_graphdb_slice", "root", "macmysql01");
        
        CytoscapJSToDiagramXMLConverter converter = new CytoscapJSToDiagramXMLConverter();
        converter.convertToRTPJFile(cytoscapeJSFile, pathwayDbId, diagramDbId, dba, outputXMLFile);
        // Here you would implement the logic to write the diagram to outputXMLFile
        System.out.println("Conversion completed. Output written to " + outputXMLFile.getAbsolutePath());
    }

}
