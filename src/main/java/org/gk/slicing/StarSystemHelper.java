package org.gk.slicing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.gk.model.GKInstance;
import org.gk.model.ReactomeJavaConstants;
import org.gk.persistence.MySQLAdaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class is used to handle the star system during the slicing. The major function of this
 * class is to assign 5 stars to events that don't have the reviewStatus assigned and check
 * events in the slice database that have lower than 3 stars.
 * @author wug
 *
 */
@SuppressWarnings("unchecked")
public class StarSystemHelper {
    private final Logger logger = LoggerFactory.getLogger(StarSystemHelper.class);

    private static final String REVIEW_STATUS = "reviewStatus";
    private static final String STRUCTURE_MODIFIED = "structureModified";
    private static final String ONE_STAR = "one star";
    private static final String TWO_STARS = "two stars";
    private static final String THREE_STARS = "three stars";
    private static final String FOUR_STARS = "four stars";
    private static final String FIVE_STARS = "five stars";

    public StarSystemHelper() {
    }

    /**
     * Extract ReviewStatus instances. All ReviewStatus instances are collected regardless whether
     * they are used or not.
     * @param dba
     * @throws Exception
     */
    public Collection<GKInstance> extractReviewStatus(MySQLAdaptor dba) throws Exception {
        return dba.fetchInstancesByClass("ReviewStatus");
    }
    
    /**
     * Assign five stars to Events in the slice that don't have reviewStatus assigned. These Events
     * are assumed to have externally reviewed.
     * @param sourceDBA
     * @param sliceMap
     * @return
     * @throws Exception
     */
    public List<GKInstance> assignFiveStarsToEvents(MySQLAdaptor sourceDBA,
                                                    Map<Long, GKInstance> sliceMap) throws Exception {
        logger.info("Assigning five stars to Events to be sliced...");
        Map<String, GKInstance> star2inst = loadReviewStatus(sourceDBA);
        GKInstance fiveStarReviewStatus = star2inst.get(FIVE_STARS);
        if (fiveStarReviewStatus == null) {
            logger.error("Error: Cannot find five stars ReviewStatus instance.");
            return Collections.EMPTY_LIST;
        }
        List<GKInstance> updatedEvents = new ArrayList<>();
        for (Long dbId : sliceMap.keySet()) {
            GKInstance inst = sliceMap.get(dbId);
            if (!inst.getSchemClass().isValidAttribute(REVIEW_STATUS)) {
                continue;
            }
            GKInstance reviewStatus = (GKInstance) inst.getAttributeValue(REVIEW_STATUS);
            if (reviewStatus == null) {
                logger.info("Assigning five stars to: " + inst);
                inst.setAttributeValue(REVIEW_STATUS, fiveStarReviewStatus);
                updatedEvents.add(inst);
            }
        }
        return updatedEvents;
    }
    
    /**
     * This method is used to handle the following scenario related to pathway: A released pathways (3, 4, and 5 stars)
     * may be added a new Event in hasEvent, resulting the demoting of the start to 1 or 2 at gk_central. However, during 
     * the slicing or release, this newly Event is flagged for not releasing. Therefore, the original pathway structure 
     * that is determined by "hasEvent" is reverted back. Therefore, we should copy the original star from the previous release
     * (or slice) back to this slice. In reality, we copy a higher star to any lower star if a pathway's hasEvent is not changed.
     * (Added on June 9, 2025) To avoid flagging the pathway for the ReviewStatus QA, we also make sure that the list of
     * structureModified instances are the same as in the old slice database after copying the reviewStatus.
     * @param priorDBA
     * @param sliceMap
     * @return
     * @throws Exception
     */
    public List<GKInstance> copyReviewStatusFromPriorSliceForPathways(MySQLAdaptor priorDBA,
                                                                      Map<Long, GKInstance> sliceMap) throws Exception {
        logger.info("Copying higher stars from previous slice for pathways having no structural update...");
        if (priorDBA == null) {
            logger.info("No priorDBA specified. Stop this step.");
            return Collections.EMPTY_LIST;
        }
        // Use the ReviewStatus CV instances already collected in sliceMap rather than re-querying a source
        // database for them: sliceMap may have been built from the graph database (GraphDBInstanceManager),
        // in which case a MySQLAdaptor-based lookup would return different instance objects than the ones
        // already referenced by pathways in sliceMap. hasEvent is assumed to be fully populated on every
        // Pathway in sliceMap already, so no source database or API call is needed to compare pathway structure.
        Map<String, GKInstance> star2inst = loadReviewStatus(sliceMap);
        // Map the review status to numbers so that we can do comparison
        Map<String, Integer> star2number = new HashMap<>();
        star2number.put(ONE_STAR, 1);
        star2number.put(TWO_STARS, 2);
        star2number.put(THREE_STARS, 3);
        star2number.put(FOUR_STARS, 4);
        star2number.put(FIVE_STARS, 5);
        List<GKInstance> updatedPathways = new ArrayList<>();
        for (Long dbId : sliceMap.keySet()) {
            GKInstance inst = sliceMap.get(dbId);
            if (!inst.getSchemClass().isValidAttribute(REVIEW_STATUS)) {
                continue;
            }
            if (!inst.getSchemClass().isa(ReactomeJavaConstants.Pathway))
                continue; // Work for pathway only
            GKInstance reviewStatus = (GKInstance) inst.getAttributeValue(REVIEW_STATUS);
            // Escape five stars: They should be good.
            if (reviewStatus != null && FIVE_STARS.equals(reviewStatus.getDisplayName()))
                continue;
            // Check the old pathway
            GKInstance oldInst = priorDBA.fetchInstance(inst.getDBID());
            if (oldInst == null)
                continue; // Nothing to do
            if (!oldInst.getSchemClass().isa(ReactomeJavaConstants.Pathway))
                continue; // It is not a pathway. Don't do anything
            if (!oldInst.getSchemClass().isValidAttribute(REVIEW_STATUS))
                continue; // Old model. Don't bother.
            List<GKInstance> oldHasEvent = oldInst.getAttributeValuesList(ReactomeJavaConstants.hasEvent);
            List<Long> oldHasEventIds = oldHasEvent.stream().map(GKInstance::getDBID).collect(Collectors.toList());
            List<GKInstance> newHasEvent = inst.getAttributeValuesList(ReactomeJavaConstants.hasEvent);
            List<Long> newHasEventIds = newHasEvent.stream()
                                                   .map(GKInstance::getDBID)
                                                   .collect(Collectors.toList());
            if (!oldHasEventIds.equals(newHasEventIds))
                continue; // The copy is applied to cases that have the same list of hasEvent only.
            // Get the old review status
            GKInstance oldReviewStatus = (GKInstance) oldInst.getAttributeValue(REVIEW_STATUS);
            if (oldReviewStatus == null)
                continue; // Nothing to copy
            Integer oldReviewStand = star2number.get(oldReviewStatus.getDisplayName());
            if (oldReviewStand == null)
                continue; // Unknown old review status. Nothing to compare against.
            // Get the stand for the new
            Integer newReviewStand = reviewStatus == null ? null : star2number.get(reviewStatus.getDisplayName());
            if (newReviewStand == null)
                newReviewStand = 0; // Put it at the bottom
            if (newReviewStand < oldReviewStand) {
                // Copy the old review status to the new. But we need to use the copy of the sourceDBA
                logger.info("Copying old reviewStatus for " + inst + ": " +
                            oldReviewStatus.getDisplayName() + "->" +
                            (reviewStatus == null ? null : reviewStatus.getDisplayName()));
                inst.setAttributeValue(REVIEW_STATUS,
                                       star2inst.get(oldReviewStatus.getDisplayName()));
                // Make sure the list of structureModified instances are the same as in the old slice database.
                // By doing this, we can avoid to flag this pathway for the ReviewStatus QA during the release.
                List<GKInstance> structureModified = inst.getAttributeValuesList(STRUCTURE_MODIFIED);
                if (structureModified != null && structureModified.size() > 0) {
                    // Reset the structureModified list
                    boolean isModified = false;
                    List<GKInstance> oldStructureModified = oldInst.getAttributeValuesList(STRUCTURE_MODIFIED);
                    Set<Long> oldStructureModifiedIds = new HashSet<>();
                    if (oldStructureModified != null && oldStructureModified.size() > 0) {
                        oldStructureModifiedIds = oldStructureModified.stream()
                                                                      .map(GKInstance::getDBID)
                                                                      .collect(Collectors.toSet());
                    }
                    for (Iterator<GKInstance> it = structureModified.iterator(); it.hasNext();) {
                        GKInstance sm = it.next();
                        if (!oldStructureModifiedIds.contains(sm.getDBID())) {
                            // This is not in the old structureModified. Remove it.
                            it.remove();
                            isModified = true;
                        }
                    }
                    if (isModified) {
                        // Update the structureModified
                        inst.setAttributeValue(STRUCTURE_MODIFIED, structureModified);
                    }
                }
                updatedPathways.add(inst);
            }
        }
        logger.info("Done copying. The number of pathways touched: " + updatedPathways.size());
        return updatedPathways;
    }
    
    /**
     * Use this method to assign five stars to released Events if these events don't have any reviewStatus 
     * assigned.
     * @param sourceDBA
     * @throws Exception
     */
    public void commitReviewStatusToSourceDB(List<GKInstance> eventsToBeUpdated,
                                             MySQLAdaptor sourceDBA,
                                             GKInstance defaultIE) throws Exception {
        logger.info("Writing back reviewStatus to the source database...");
        logger.info("Total Events to be updated for this step: " + eventsToBeUpdated.size());
        boolean needTransaction = sourceDBA.supportsTransactions();
        try {
            if (needTransaction)
                sourceDBA.startTransaction();
            // DefaultIE has not been stored
            if (defaultIE.getDBID() == null || defaultIE.getDBID() < 0)
                sourceDBA.storeInstance(defaultIE);
            int count = 1;
            for (GKInstance event : eventsToBeUpdated) {
                logger.info(count + ": " + event);
                sourceDBA.updateInstanceAttribute(event, REVIEW_STATUS);
                event.getAttributeValuesList(ReactomeJavaConstants.modified);
                event.addAttributeValue(ReactomeJavaConstants.modified, defaultIE);
                sourceDBA.updateInstanceAttribute(event, ReactomeJavaConstants.modified);
                count ++;
            }
            if (needTransaction) 
                sourceDBA.commit();
            logger.info("Total processed events: " + (count - 1));
        }
        catch(Exception e) {
            if (needTransaction)
                sourceDBA.rollback();
            logger.error("Cannot commit reviewStatus: " + e.getMessage(), e);
            throw e;
        }
    }
    
    private Map<String, GKInstance> loadReviewStatus(MySQLAdaptor dba) throws Exception {
        Map<String, GKInstance> name2instance = new HashMap<>();
        Collection<GKInstance> instances = dba.fetchInstancesByClass("ReviewStatus");
        for (GKInstance instance : instances) {
            name2instance.put(instance.getDisplayName(), instance);
        }
        return name2instance;
    }

    /**
     * Same purpose as {@link #loadReviewStatus(MySQLAdaptor)}, but reads the ReviewStatus CV instances
     * already extracted into sliceMap instead of querying a MySQLAdaptor for them. This is needed for a
     * graph-database-sourced slice, where the ReviewStatus instances in sliceMap are not tied to any
     * MySQLAdaptor's instance cache.
     */
    private Map<String, GKInstance> loadReviewStatus(Map<Long, GKInstance> sliceMap) {
        Map<String, GKInstance> name2instance = new HashMap<>();
        for (GKInstance instance : sliceMap.values()) {
            if (instance.getSchemClass().isa("ReviewStatus"))
                name2instance.put(instance.getDisplayName(), instance);
        }
        return name2instance;
    }

}
