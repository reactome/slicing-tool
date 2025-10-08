package org.gk.slicing;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.gk.database.EventCheckOutHandler;
import org.gk.database.SynchronizationManager;
import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.DiagramGKBWriter;
import org.gk.persistence.MySQLAdaptor;
import org.gk.persistence.PersistenceManager;
import org.gk.persistence.XMLFileAdaptor;
import org.gk.render.FlowLine;
import org.gk.render.HyperEdge;
import org.gk.render.Node;
import org.gk.render.ProcessNode;
import org.gk.render.ReactionType;
import org.gk.render.Renderable;
import org.gk.render.RenderableChemical;
import org.gk.render.RenderableCompartment;
import org.gk.render.RenderableComplex;
import org.gk.render.RenderableEntity;
import org.gk.render.RenderableEntitySet;
import org.gk.render.RenderableFeature;
import org.gk.render.RenderableGene;
import org.gk.render.RenderablePathway;
import org.gk.render.RenderableProtein;
import org.gk.render.RenderableProteinDrug;
import org.gk.render.RenderableRNA;
import org.gk.render.RenderableRNADrug;
import org.gk.render.RenderableReaction;
import org.gk.render.RenderableRegistry;
import org.reactome.curation.model.InstanceList;
import org.reactome.curation.model.SimpleInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * This class is responsible for converting Cytoscape.js JSON representations of biological pathways to the Reactome Diagram XML format.
 */
@SuppressWarnings("unchecked")
public class CytoscapJSToDiagramXMLConverter {
    private static final Logger logger = LoggerFactory.getLogger(CytoscapJSToDiagramXMLConverter.class);
    // Use to check if two points are the same
    private static final double TOLERANCE = 1.5d;

    public CytoscapJSToDiagramXMLConverter() {
    }
    
    public String convert(File cytoscapeJSFile,
                         GKInstance pathwayInstance,
                         GKInstance diagramInstance) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(cytoscapeJSFile);
        
        RenderablePathway diagram = new RenderablePathway();
        diagram.setReactomeDiagramId(diagramInstance.getDBID());
        diagram.setHideCompartmentInNode(true);
        convert(root, diagram, (MySQLAdaptor)pathwayInstance.getDbAdaptor());

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

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(cytoscapeJSFile);
        
        RenderablePathway diagram = new RenderablePathway();
        diagram.setReactomeDiagramId(diagramDbId);
        diagram.setHideCompartmentInNode(true);
        convert(root, diagram, dba);

        // Convert diagram to xml
        DiagramGKBWriter writer = new DiagramGKBWriter();
        String diagramXML = writer.generateXMLString(diagram);
        System.out.println(diagramXML);
        diagramInstance.setAttributeValue(ReactomeJavaConstants.storedATXML, diagramXML);

        return diagramInstance;
    }

    private void convert(JsonNode cytoscapeNode, RenderablePathway diagram, MySQLAdaptor dba) throws Exception {
        // Implement the logic to convert Cytoscape.js JSON nodes to RenderablePathway elements
        // Elements block has nodes + edges
        JsonNode elements = cytoscapeNode.path("elements");

        /* ---- Traverse Nodes ---- */
        JsonNode nodes = elements.path("nodes");
        // Keep reaction nodes for later use
        Map<Long, JsonNode> reactionIdToNode = new java.util.HashMap<>();
        Map<Integer, Renderable> idToRenderable = new java.util.HashMap<>();
        if (nodes.isArray()) {
            // Handle compartments in the second pass.
            Map<Integer, List<JsonNode>> id2Compartment = new java.util.HashMap<>();
            // Keep modifications for the second pass
            List<JsonNode> modifications = new ArrayList<>();
            for (JsonNode node : nodes) {
                JsonNode data = node.path("data");
                String id = data.path("id").asText();
                String reactomeId = data.path("reactomeId").asText("");
                String classes = node.path("classes").asText("");
                
                // The following types of nodes are not handled for now
                if (classes.contains("reaction")) {
                    // Just store it for now
                    if (!reactomeId.isEmpty()) {
                        reactionIdToNode.put(Long.parseLong(reactomeId), node);
                    }
                    else
                        logger.error("Cannot find Reactome id for reaction node id: " + id + ". Skipping it for now.");
                    continue; // Skip reactions for now
                }
                else if (classes.contains("Modification")) {
                    modifications.add(node);
                    continue; // Skip modifications for now
                }
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

                Renderable renderable = createNode(classes, id);
                if (renderable == null) {
                    logger.warn("Unsupported node class: " + classes + " for node id: " + id);
                    continue;
                }

                renderable.setReactomeId(Long.parseLong(reactomeId));
                renderable.setID(Integer.parseInt(id));
                idToRenderable.put(renderable.getID(), renderable);
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
            handleModifications(modifications, idToRenderable);
        }

        /* ---- Traverse Edges ---- */
        JsonNode edges = elements.path("edges");
        Map<Long, List<JsonNode>> reactionIdToEdges = new java.util.HashMap<>();
        // Flowlines need to be handled differently since they don't have reactomeId
        List<JsonNode> flowLines = new ArrayList<>();
        // Assign edges to individual reactions for converting
        if (edges.isArray()) {
            for (JsonNode edge : edges) {
                JsonNode data = edge.path("data");
                String edgeType = data.path("edgeType").asText("");
                if (edgeType.equals("FlowLine")) {
                    flowLines.add(edge);
                    continue; // Skip flowlines for now
                }
                String id = data.path("id").asText();
                String reactomeId = data.path("reactomeId").asText("");
                if (reactomeId.isEmpty()) {
                    logger.error("Skipping edge without reactomeId: " + id);
                    continue; // Skip edges without reactomeId
                }
                Long reactionDbId = Long.parseLong(reactomeId);
                List<JsonNode> list = reactionIdToEdges.get(reactionDbId);
                if (list == null) {
                    list = new ArrayList<>();
                    reactionIdToEdges.put(reactionDbId, list);
                }   
                list.add(edge);
            }
        }
        // Make sure we have to register all nodes first
        RenderableRegistry.getRegistry().clear();
        RenderableRegistry.getRegistry().registerAll(diagram);
        // Need to reset nextId to avoid ID conflicts
        RenderableRegistry.getRegistry().resetNextIdFromPathway(diagram);
        // Now convert edges for each reaction
        for (Long reactionDbId : reactionIdToEdges.keySet()) {
            JsonNode reactionNode = reactionIdToNode.get(reactionDbId);
            if (reactionNode == null) {
                logger.error("Cannot find reaction node for reaction with dbId: " + reactionDbId + ". Skipping it.");
                continue;
            }
            List<JsonNode> edgesForReaction = reactionIdToEdges.get(reactionDbId);
            HyperEdge edge = convertEdges(edgesForReaction, reactionNode, idToRenderable);
            diagram.addComponent(edge);
        }
        // Now handle flowlines
        for (JsonNode flowLine : flowLines) {
            FlowLine line = convertFlowLine(flowLine, idToRenderable);
            diagram.addComponent(line);
        }
    }
    
    private Node createNode(String classes, String id) {
        Node renderable = null;
        if (classes.contains("Drug")) { // To be tested
            if (classes.contains("Protein")) 
                renderable = new RenderableProteinDrug();
            else if (classes.contains("RNA"))
                renderable = new RenderableRNADrug();
            // This is default for drug.
            renderable = new RenderableChemical();
        }
        if (classes.contains("Protein")) 
            renderable = new RenderableProtein();
        else if (classes.contains("Gene"))
            renderable = new RenderableGene();
        else if (classes.contains("RNA"))
            renderable = new RenderableRNA();
        else if (classes.contains("EntitySet")) 
            renderable = new RenderableEntitySet();
        else if (classes.contains("Molecule")) 
            renderable = new RenderableChemical();
        else if (classes.contains("Complex")) {
            renderable = new RenderableComplex();
            ((RenderableComplex)renderable).hideComponents(true);
        }
        else if (classes.contains("Pathway"))
            renderable = new ProcessNode();
        else if (classes.contains("PhysicalEntity")) {
            renderable = new RenderableEntity(); // Default. Shouldn't be here usually!
            logger.warn("Using generic RenderableEntity for node id: " + id);
        }
        return renderable;
    }
    
    private void handleModifications(List<JsonNode> modifications, Map<Integer, Renderable> id2Renderable) {
        for (JsonNode modification : modifications) {
            Long modDbId = modification.path("data").path("reactomeId").asLong();
            Integer nodeId = modification.path("data").path("nodeId").asInt();
            Renderable renderable = id2Renderable.get(nodeId);
            if (renderable == null) {
                logger.error("Cannot find node for modification with dbId: " + modDbId + " and nodeId: " + nodeId);
                continue;
            }
            if (!(renderable instanceof Node)) {
                logger.error("Modification with dbId: " + modDbId + " is attached to a non-Node Renderable with id: " + nodeId);
                continue;
            }
            // We will use RenderableFeature to represent modification
            RenderableFeature attachment = new RenderableFeature();
            String label = modification.path("data").path("displayName").asText("");
            attachment.setLabel(label);
            attachment.setReactomeId(modDbId);
            // Need to get relative position to the top-left corner of the node
            double x = modification.path("position").path("x").asDouble();
            double y = modification.path("position").path("y").asDouble();
            Node node = (Node) renderable;
            Rectangle nodeBounds = node.getBounds();
            double relativeX = (x - nodeBounds.x) / nodeBounds.width;
            double relativeY = (y - nodeBounds.y) / nodeBounds.height;
            attachment.setRelativePosition(relativeX, relativeY);
            
            node.addFeatureLocally(attachment);
        }
    }
    
    private Point existPoint(Point p, Collection<Point> points) {
        for (Point point : points) {
            if (Math.abs(point.x - p.x) < TOLERANCE &&
                Math.abs(point.y - p.y) < TOLERANCE)
                return point;
        }
        return null;
    }

    
    /**
     * Flowline we need to handle backbone points only. This is just a simple edge.
     * @param flowLine
     * @param idToRenderable
     * @return
     */
    private FlowLine convertFlowLine(JsonNode flowLine,
                                     Map<Integer, Renderable> idToRenderable) {
        List<Point> points = positionsFromRelativeToAbsolute(flowLine, null, idToRenderable);
        FlowLine line = new FlowLine();
        line.setBackbonePoints(points);
        // Need to add input and output
        Integer sourceId = flowLine.path("data").path("source").asInt();
        Integer targetId = flowLine.path("data").path("target").asInt();
        Renderable source = idToRenderable.get(sourceId);
        Renderable target = idToRenderable.get(targetId);
        if (source != null)
            line.addInput((Node)source);
        else
            logger.error("Cannot find source node for flowline with id: " + flowLine.path("data").path("id").asText());
        if (target != null)
            line.addOutput((Node)target);
        else
            logger.error("Cannot find target node for flowline with id: " + flowLine.path("data").path("id").asText());
        // Just ignore id since the id may not be an integer
//        line.setID(Integer.parseInt(flowLine.path("data").path("id").asText()));
        return line;
    }

    /**
     * Note: Some of the code is ported from TypeScript code: 
     * https://github.com/reactome/curator-tool-frontend/blob/3335aec5f28a4e2d12a690c5602e137212f9cfa6/
     * src/app/event-view/components/pathway-diagram/utils/pathway-diagram-utils.ts#L714
     * @param edges
     * @param reactionNode
     * @return
     */
    private HyperEdge convertEdges(List<JsonNode> edges,
                                   JsonNode reactionNode,
                                   Map<Integer, Renderable> idToRenderable) {
        // Build and initialize the HyperEdge
        HyperEdge hyperEdge = buildHyperEdge(reactionNode);

        // Classify edges by their role
        Map<String, List<JsonNode>> classifiedEdges = classifyEdges(edges);

        // Process different edge categories
        List<Point> backbonePoints = new ArrayList<>();

        // The order of processing inputs and outputs matters for setting backbone points
        processIOEdges(classifiedEdges.get("inputs"), true, hyperEdge, reactionNode, idToRenderable, backbonePoints);
        processIOEdges(classifiedEdges.get("outputs"), false, hyperEdge, reactionNode, idToRenderable, backbonePoints);
        processHelperEdges(classifiedEdges.get("activators"), "activator", hyperEdge, reactionNode, idToRenderable);
        processHelperEdges(classifiedEdges.get("catalysts"), "catalyst", hyperEdge, reactionNode, idToRenderable);
        processHelperEdges(classifiedEdges.get("inhibitors"), "inhibitor", hyperEdge, reactionNode, idToRenderable);

        if (!backbonePoints.isEmpty())
            hyperEdge.setBackbonePoints(backbonePoints);

        return hyperEdge;
    }
    
    private HyperEdge buildHyperEdge(JsonNode reactionNode) {   
        HyperEdge hyperEdge = new RenderableReaction();
        hyperEdge.setReactomeId(reactionNode.path("data").path("reactomeId").asLong());
        RenderableRegistry.getRegistry().add(hyperEdge);
        
        // Need to set position based on the backbone points. No need to set it here.
        
        String classes = reactionNode.path("classes").asText("");
        if (!classes.isEmpty()) {
            String type = classes.split(" ")[0].replace(" ", "_");
            ((RenderableReaction) hyperEdge).setReactionType(ReactionType.valueOf(type.toUpperCase()));
        }
        return hyperEdge;
    }
    
    private Map<String, List<JsonNode>> classifyEdges(List<JsonNode> edges) {
        Map<String, List<JsonNode>> map = new HashMap<>();
        map.put("inputs", new ArrayList<>());
        map.put("outputs", new ArrayList<>());
        map.put("catalysts", new ArrayList<>());
        map.put("inhibitors", new ArrayList<>());
        map.put("activators", new ArrayList<>());

        for (JsonNode edge : edges) {
            String cls = edge.path("classes").asText("");
            if (cls.contains("input") || cls.contains("consumption"))
                map.get("inputs").add(edge);
            else if (cls.contains("output") || cls.contains("production"))
                map.get("outputs").add(edge);
            else if (cls.contains("catalysis"))
                map.get("catalysts").add(edge);
            else if (cls.contains("negative-regulation"))
                map.get("inhibitors").add(edge);
            else if (cls.contains("positive-regulation"))
                map.get("activators").add(edge);
            else
                logger.warn("Unsupported edge class: {} for edge id: {}", cls, edge.path("data").path("id").asText());
        }

        return map;
    }
    
    private void processIOEdges(List<JsonNode> ioEdges,
                                boolean isInput,
                                HyperEdge hyperEdge,
                                JsonNode reactionNode,
                                Map<Integer, Renderable> idToRenderable,
                                List<Point> backbonePoints) {
        if (ioEdges == null || ioEdges.isEmpty()) return;

        if (ioEdges.size() == 1) {
            JsonNode edge = ioEdges.get(0);
            Integer nodeId = edge.path("data").path(isInput ? "source" : "target").asInt();
            Renderable node = idToRenderable.get(nodeId);

            if (node != null) {
                if (isInput) hyperEdge.addInput((Node) node);
                else hyperEdge.addOutput((Node) node);
            } else {
                logger.error("Cannot find {} node for {} edge id: {}",
                        isInput ? "source" : "target",
                        isInput ? "input" : "output",
                        edge.path("data").path("id").asText());
                return;
            }

            List<Point> points = positionsFromRelativeToAbsolute(edge, reactionNode, idToRenderable);
            if (points != null && !points.isEmpty()) {
                if (isInput) hyperEdge.setPosition(points.get(points.size() - 1));
                if (!isInput) points = points.subList(1, points.size());
                backbonePoints.addAll(points);
            }
        } 
        else {
            List<List<Point>> groupedPoints = new ArrayList<>();
            for (JsonNode e : ioEdges)
                groupedPoints.add(positionsFromRelativeToAbsolute(e, reactionNode, idToRenderable));

            List<Point> sharedPoints = findSharedPoints(groupedPoints);
            if (!sharedPoints.isEmpty()) { // This should always be true
                if (isInput) {
                    hyperEdge.setPosition(sharedPoints.get(sharedPoints.size() - 1));
                    backbonePoints.addAll(sharedPoints);
                }
                else {
                    backbonePoints.addAll(sharedPoints.subList(1, sharedPoints.size()));
                }
                // Need to clean up groupedPoints to remove shared points
                for (List<Point> pts : groupedPoints) {
                    for (Iterator<Point> it = pts.iterator(); it.hasNext();) {
                        Point p = it.next();
                        if (existPoint(p, sharedPoints) != null)
                            it.remove();
                    }
                }
            }

            if (isInput) hyperEdge.setInputPoints(groupedPoints);
            else hyperEdge.setOutputPoints(groupedPoints);

            for (JsonNode e : ioEdges) {
                Integer nodeId = e.path("data").path(isInput ? "source" : "target").asInt();
                Renderable node = idToRenderable.get(nodeId);
                if (node != null) {
                    if (isInput) hyperEdge.addInput((Node) node);
                    else hyperEdge.addOutput((Node) node);
                } else {
                    logger.error("Cannot find {} node for edge id: {}", isInput ? "source" : "target", e.path("data").path("id").asText());
                }
            }
        }
    }
    
    private void processHelperEdges(List<JsonNode> edges,
                                    String type,
                                    HyperEdge hyperEdge,
                                    JsonNode reactionNode,
                                    Map<Integer, Renderable> idToRenderable) {
        if (edges == null || edges.isEmpty()) return;

        List<List<Point>> helperPoints = new ArrayList<>();
        for (JsonNode e : edges) {
            List<Point> pts = positionsFromRelativeToAbsolute(e, reactionNode, idToRenderable);
            if (pts != null && pts.size() > 1)
                helperPoints.add(pts.subList(0, pts.size() - 1)); // skip last point
        }

        switch (type) {
            case "activator": 
                hyperEdge.setActivatorPoints(helperPoints);
            case "catalyst" :
                hyperEdge.setHelperPoints(helperPoints);
            case "inhibitor" :
                hyperEdge.setInhibitorPoints(helperPoints);
        }

        for (JsonNode e : edges) {
            Integer srcId = e.path("data").path("source").asInt();
            Renderable src = idToRenderable.get(srcId);
            Node node = (src instanceof Node) ? (Node) src : null;

            switch (type) {
                case "activator" : hyperEdge.addActivator(node);
                case "catalyst" : hyperEdge.addHelper(node);
                case "inhibitor" : hyperEdge.addInhibitor(node);
            }

            if (src == null)
                logger.error("Cannot find source node for {} edge id: {}", type, e.path("data").path("id").asText());
        }
    }
    
    private List<Point> findSharedPoints(List<List<Point>> inputPoints) {
        Map<Point, Integer> countMap = new HashMap<>();
        for (List<Point> points : inputPoints) {
            for (Point p : points) {
                Point existing = existPoint(p, countMap.keySet());
                countMap.put(existing != null ? existing : p, countMap.getOrDefault(existing != null ? existing : p, 0) + 1);
            }
        }
        List<Point> shared = new ArrayList<>();
        List<Point> firstList = inputPoints.get(0);
        for (Point p : firstList) {
            Integer count = countMap.get(p);
            if (count != null && count == inputPoints.size())
                shared.add(p);
        }
        return shared;
    }
    

    /**
     * Convert Cytoscape.js-style edge relative positions into absolute coordinates.
     */
    private List<Point> positionsFromRelativeToAbsolute(JsonNode edge, 
                                                       JsonNode reactionNode,
                                                       Map<Integer, Renderable> id2node) {
        
        // Get source and target nodes
        String sourceId = edge.path("data").path("source").asText();
        String targetId = edge.path("data").path("target").asText();
        
        // Get source and target positions
        String rxtNodeId = null;
        if (reactionNode != null)
            rxtNodeId = reactionNode.path("data").path("id").asText();
        
        Point sourcePos = null;
        if (rxtNodeId != null && sourceId.equals(rxtNodeId)) {
            double x = reactionNode.path("position").path("x").asDouble();
            double y = reactionNode.path("position").path("y").asDouble();
            sourcePos = new Point((int)x, (int)y);
        }
        else {
            Renderable sourceNode = id2node.get(Integer.parseInt(sourceId));
            if (sourceNode != null)
                sourcePos = new Point(sourceNode.getPosition());
            else
                logger.error("Cannot find source node for edge with id: " + edge.path("data").path("id").asText());
        }

        Point targetPos = null;
        if (rxtNodeId != null && targetId.equals(rxtNodeId)) {
            double x = reactionNode.path("position").path("x").asDouble();
            double y = reactionNode.path("position").path("y").asDouble();
            targetPos = new Point((int)x, (int)y);
        }
        else {
            Renderable targetNode = id2node.get(Integer.parseInt(targetId));
            if (targetNode != null)
                targetPos = new Point(targetNode.getPosition());
            else
                logger.error("Cannot find target node for edge with id: " + edge.path("data").path("id").asText());
        }
        
        List<Point> points = new ArrayList<>();
        points.add(sourcePos);
        points.add(targetPos);
        // Get distances
        String distancesStr = edge.path("data").path("distances").asText("");
        if (distancesStr == null || distancesStr.isEmpty())
            return points;

        String[] distancesTokens = distancesStr.split(" ");
        double[] distances = Arrays.stream(distancesTokens)
                .mapToDouble(Double::parseDouble)
                .toArray();

        // Get weights
        String weightsStr = edge.path("data").path("weights").asText("");
        if (weightsStr == null || weightsStr.isEmpty())
            return points;

        String[] weightsTokens = weightsStr.split(" ");
        double[] weights = Arrays.stream(weightsTokens)
                .mapToDouble(Double::parseDouble)
                .toArray();
        
        // Source coordinates
        double[] sourceEndpoint = parseDoubles(edge.path("data").path("sourceEndpoint").asText());
        sourcePos = new Point(
                (int)(sourcePos.x + sourceEndpoint[0]),
                (int)(sourcePos.y + sourceEndpoint[1])
                );

        // Target coordinates
        double[] targetEndpoint = parseDoubles(edge.path("data").path("targetEndpoint").asText());
        targetPos = new Point(
                (int)(targetPos.x + targetEndpoint[0]),
                (int)(targetPos.y + targetEndpoint[1])
                );

        List<Point> bendingPoints = relativeToAbsolute(sourcePos, targetPos, distances, weights);
        bendingPoints.add(0, sourcePos); // Add source at the beginning
        bendingPoints.add(targetPos); // Add target at the end
        return bendingPoints;
    }
    
    private double[] parseDoubles(String s) {
        String[] parts = s.trim().split("\\s+");
        double[] result = new double[parts.length];
        for (int i = 0; i < parts.length; i++)
            result[i] = Double.parseDouble(parts[i]);
        return result;
    }

    /**
     * Convert relative positions to absolute positions (reverse of absoluteToRelative()).
     */
    private List<Point> relativeToAbsolute(Point source,
                                           Point target,
                                           double[] distances,
                                           double[] weights) {
        double dx = target.x - source.x;
        double dy = target.y - source.y;

        // Edge vector (main)
        double[] mainVector = new double[] { dx, dy };

        // Perpendicular vector (normalized)
        double len = Math.sqrt(dx * dx + dy * dy);
        double[] orthoVector = new double[] { -dy / len, dx / len };

        // Transformation matrix
        double[][] transform = new double[][] {
            { mainVector[0], orthoVector[0] },
            { mainVector[1], orthoVector[1] }
        };

        List<Point> absolutePositions = new ArrayList<>();
        for (int i = 0; i < distances.length; i++) {
            double w = weights[i];
            double d = distances[i];

            // Multiply [w, d] by transform matrix
            double absX = w * transform[0][0] + d * transform[0][1] + source.x;
            double absY = w * transform[1][0] + d * transform[1][1] + source.y;

            absolutePositions.add(new Point((int)Math.round(absX), (int)Math.round(absY)));
        }

        return absolutePositions;
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
        Map<String, Long> nameToIdMap = fetchCompartmentNameToIdMap();
        List<RenderableCompartment> compartments = new ArrayList<>();
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
            compartments.add(compartment);
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
        // Need to set compartment's components
        for (RenderableCompartment compartment : compartments) {
            for (Renderable r : (List<Renderable>)diagram.getComponents()) {
                if (compartment.isAssignable(r)) {
                    compartment.addComponent(r);
                }
            }
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
