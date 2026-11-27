package org.openstreetmap.josm.plugins.plbuildings.actions.importstrategy;

import static org.openstreetmap.josm.plugins.plbuildings.commands.CommandWithErrorReason.getLatestErrorReasonStatus;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.AbstractPrimitive;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.plbuildings.BuildingsImportManager;
import org.openstreetmap.josm.plugins.plbuildings.BuildingsSettings;
import org.openstreetmap.josm.plugins.plbuildings.commands.AddBuildingGeometryCommand;
import org.openstreetmap.josm.plugins.plbuildings.commands.CommandResultBuilding;
import org.openstreetmap.josm.plugins.plbuildings.commands.ReplaceBuildingGeometryCommand;
import org.openstreetmap.josm.plugins.plbuildings.commands.UpdateBuildingTagsCommand;
import org.openstreetmap.josm.plugins.plbuildings.data.BuildingsTags;
import org.openstreetmap.josm.plugins.plbuildings.enums.ImportStatus;
import org.openstreetmap.josm.plugins.plbuildings.exceptions.ImportActionCanceledException;
import org.openstreetmap.josm.plugins.plbuildings.models.BuildingsImportStats;
import org.openstreetmap.josm.plugins.plbuildings.utils.SharedNodesUtils;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Pair;

public class FullImportStrategy extends ImportStrategy {
    public FullImportStrategy(BuildingsImportManager manager, BuildingsImportStats importStats, Way importedBuilding) {
        super(manager, importStats, importedBuilding);
    }

    private Way handleImportUpdateTagsOnly(Way selectedBuilding) throws ImportActionCanceledException {
        Logging.info("Duplicated building geometry. Trying to update tags!");
        UpdateBuildingTagsCommand updateBuildingTagsCommand = new UpdateBuildingTagsCommand(
            currentDataSet,
            () -> selectedBuilding,
            importedBuilding
        );
        boolean isUpdated = updateBuildingTagsCommand.executeCommand();
        if (!isUpdated) {
            throw new ImportActionCanceledException(
                updateBuildingTagsCommand.getErrorReason(),
                updateBuildingTagsCommand.getErrorStatus()
            );
        }
        UndoRedoHandler.getInstance().add(updateBuildingTagsCommand, false);
        importStats.addImportWithReplaceCounter(1);
        importStats.addImportWithTagsUpdateCounter(1);
        Logging.info("Updated selected building tags (without geometry replacing)!");

        return selectedBuilding;

    }

    private Way handleImportNewBuilding() throws ImportActionCanceledException {
        Logging.info("Importing new building (without geometry replacing)!");
        AddBuildingGeometryCommand addBuildingGeometryCommand = new AddBuildingGeometryCommand(
            currentDataSet,
            importedBuilding,
            true
        );
        // Here it can be checked for detached/semi/terrace
        UpdateBuildingTagsCommand updateBuildingTagsCommand = new UpdateBuildingTagsCommand(
            currentDataSet,
            addBuildingGeometryCommand,
            importedBuilding
        );

        List<Command> commands = Arrays.asList(addBuildingGeometryCommand, updateBuildingTagsCommand);
        SequenceCommand importedNewBuildingSequence = new SequenceCommand(tr("Imported a new building"), commands);
        boolean isSuccess = importedNewBuildingSequence.executeCommand();
        if (!isSuccess) {
            Logging.debug("Importing a new building failed!");
            Pair<String, ImportStatus> errorReasonStatus = getLatestErrorReasonStatus(commands);
            throw new ImportActionCanceledException(errorReasonStatus.a, errorReasonStatus.b);
        }
        UndoRedoHandler.getInstance().add(importedNewBuildingSequence, false);
        importStats.addImportNewBuildingCounter(1);
        Logging.debug("Imported building: {0}", addBuildingGeometryCommand.getResultBuilding().getId());

        return addBuildingGeometryCommand.getResultBuilding();
    }

    private Way handleImportAndUpdateGeometryAndTags(Way selectedBuilding) throws ImportActionCanceledException {
        Logging.info("Importing new building (with geometry replacing and tags update)!");

        AddBuildingGeometryCommand addBuildingGeometryCommand = new AddBuildingGeometryCommand(
            currentDataSet,
            importedBuilding
        );
        ReplaceBuildingGeometryCommand replaceBuildingGeometryCommand = new ReplaceBuildingGeometryCommand(
            currentDataSet,
            selectedBuilding,
            addBuildingGeometryCommand
        );
        UpdateBuildingTagsCommand updateBuildingTagsCommand = new UpdateBuildingTagsCommand(
            currentDataSet,
            () -> selectedBuilding,
            importedBuilding,
            true
        );

        List<Command> commands = Arrays.asList(
            addBuildingGeometryCommand,
            replaceBuildingGeometryCommand,
            updateBuildingTagsCommand
        );
        SequenceCommand mergedGeometryAndUpdatedTagsBuildingSequence = new SequenceCommand(
            tr("Updated building tags and geometry"),
            commands
        );
        boolean isSuccess = mergedGeometryAndUpdatedTagsBuildingSequence.executeCommand();
        if (!isSuccess) {
            Logging.debug("Update (geometry and tags) building failed!");
            Pair<String, ImportStatus> errorReasonStatus = getLatestErrorReasonStatus(commands);
            throw new ImportActionCanceledException(errorReasonStatus.a, errorReasonStatus.b);
        }
        UndoRedoHandler.getInstance().add(mergedGeometryAndUpdatedTagsBuildingSequence, false);
        importStats.addImportWithReplaceCounter(1);
        importStats.addImportWithGeometryUpdateCounter(1);
        importStats.addImportWithTagsUpdateCounter(1);
        Logging.debug("Updated building {0} with new data", selectedBuilding.getId());
        return selectedBuilding;
    }

    @Override
    public Way performImport() throws ImportActionCanceledException {
        // Several selected building parts -> import them as one building and extract the addresses
        // of the parts as separate address nodes.
        List<Way> selectedBuildings = manager.getSelectedBuildings();
        if (selectedBuildings.size() > 1) {
            return handleImportReplacingMultipleBuildings(selectedBuildings);
        }

        // Pre-check/modify import data section
        Way selectedBuilding = manager.getSelectedBuilding();
        if (selectedBuilding != null) {
            tryPreventImportIfDataFromSurvey();
            trySimplifyBuildingValue();
            tryPreventBreakingRoofLevels();
        }

        // general import section
        if (isBuildingDuplicate()) {
            if (selectedBuilding == null) {
                throw new ImportActionCanceledException(
                    tr("Duplicated building geometry, without a selected building. Canceling!"),
                    ImportStatus.NO_UPDATE
                );
            }
            return handleImportUpdateTagsOnly(selectedBuilding);
        }

        if (selectedBuilding == null) {
            return handleImportNewBuilding();
        } else {
            return handleImportAndUpdateGeometryAndTags(selectedBuilding);
        }
    }

    private Way handleImportReplacingMultipleBuildings(List<Way> oldBuildings) throws ImportActionCanceledException {
        Logging.info("Importing one building replacing {0} selected parts!", oldBuildings.size());

        // The parts must not be members of relations – deleting them would break the relations.
        boolean anyInRelation = oldBuildings.stream().anyMatch(
            way -> way.getReferrers().stream().anyMatch(Relation.class::isInstance)
        );
        if (anyInRelation) {
            throw new ImportActionCanceledException(
                tr("Cannot import: one of the selected parts is a member of a relation!"),
                ImportStatus.IMPORT_ERROR
            );
        }

        // The addresses of the imported building are dropped – they come from the old parts as
        // separate address nodes instead.
        for (String key : new ArrayList<>(importedBuilding.getKeys().keySet())) {
            if (key.startsWith("addr:")) {
                importedBuilding.remove(key);
            }
        }

        AddBuildingGeometryCommand addBuildingGeometryCommand = new AddBuildingGeometryCommand(
            currentDataSet, importedBuilding, true
        );
        UpdateBuildingTagsCommand updateBuildingTagsCommand = new UpdateBuildingTagsCommand(
            currentDataSet,
            addBuildingGeometryCommand,
            importedBuilding,
            true
        );

        List<Command> commands = new ArrayList<>();
        commands.add(addBuildingGeometryCommand);
        commands.add(updateBuildingTagsCommand);

        // Extract the addresses and the services of the old parts as separate nodes placed at the
        // part centres. A single service shares the node with the part's address (a shop with its
        // address is one point); multiple services get one node each.
        for (Way oldBuilding : oldBuildings) {
            Map<String, String> addressTags = new LinkedHashMap<>();
            Map<String, String> serviceTags = new LinkedHashMap<>();
            for (Map.Entry<String, String> tag : oldBuilding.getKeys().entrySet()) {
                if (tag.getKey().startsWith("addr:")) {
                    addressTags.put(tag.getKey(), tag.getValue());
                } else if (BuildingsTags.SERVICE_TAGS.contains(tag.getKey())) {
                    serviceTags.put(tag.getKey(), tag.getValue());
                }
            }
            if (addressTags.isEmpty() && serviceTags.isEmpty()) {
                continue;
            }
            LatLon center = oldBuilding.getBBox().getCenter();
            boolean singleService = serviceTags.size() == 1;

            if (!addressTags.isEmpty()) {
                Node addressNode = new Node(center);
                addressTags.forEach(addressNode::put);
                if (singleService) {
                    serviceTags.forEach(addressNode::put);
                    String name = oldBuilding.get("name");
                    if (name != null) {
                        addressNode.put("name", name);
                    }
                }
                commands.add(new AddCommand(currentDataSet, addressNode));
                Logging.info("Extracted address node for {0}", addressTags);
            }
            if (singleService && addressTags.isEmpty()) {
                Node serviceNode = new Node(center);
                serviceTags.forEach(serviceNode::put);
                String name = oldBuilding.get("name");
                if (name != null) {
                    serviceNode.put("name", name);
                }
                commands.add(new AddCommand(currentDataSet, serviceNode));
                Logging.info("Extracted service node for {0}", serviceTags);
            } else if (!singleService) {
                for (Map.Entry<String, String> service : serviceTags.entrySet()) {
                    Node serviceNode = new Node(center);
                    serviceNode.put(service.getKey(), service.getValue());
                    commands.add(new AddCommand(currentDataSet, serviceNode));
                    Logging.info("Extracted service node for {0}={1}", service.getKey(), service.getValue());
                }
            }
        }

        // Keep the attached nodes (doors, cameras, fence attachments) – they are moved onto the new
        // building outline instead of being orphaned by the deletion of the old parts.
        List<Node> nodesToKeepAttached = getNodesToKeepAttached(oldBuildings);
        if (!nodesToKeepAttached.isEmpty()) {
            commands.add(new AttachNodesToBuildingCommand(
                currentDataSet, addBuildingGeometryCommand, nodesToKeepAttached
            ));
        }

        // Delete the old parts together with their now-unreferenced untagged nodes. The tagged
        // nodes (e.g. doors) were already attached to the new building outline by the previous
        // command, so they are not deleted nor orphaned.
        commands.add(new DeleteOldPartsCommand(currentDataSet, oldBuildings));

        SequenceCommand sequence = new SequenceCommand(
            tr("Imported one building replacing selected parts"), commands
        );
        boolean isSuccess = sequence.executeCommand();
        if (!isSuccess) {
            Logging.debug("Importing one building replacing selected parts failed!");
            Pair<String, ImportStatus> errorReasonStatus = getLatestErrorReasonStatus(commands);
            throw new ImportActionCanceledException(errorReasonStatus.a, errorReasonStatus.b);
        }
        UndoRedoHandler.getInstance().add(sequence, false);
        importStats.addImportWithReplaceCounter(oldBuildings.size());
        importStats.addImportWithGeometryUpdateCounter(1);
        Logging.info(
            "Imported building {0} replacing {1} selected parts!",
            addBuildingGeometryCommand.getResultBuilding().getId(),
            oldBuildings.size()
        );
        return addBuildingGeometryCommand.getResultBuilding();
    }

    /**
     * Collects the nodes of the old parts which must stay attached to the building (doors, cameras,
     * fence attachments). Nodes shared with other buildings or landuse areas are left untouched.
     */
    private static List<Node> getNodesToKeepAttached(List<Way> oldBuildings) {
        Set<Node> nodesToKeep = new LinkedHashSet<>();
        for (Way oldBuilding : oldBuildings) {
            for (Node node : oldBuilding.getNodes()) {
                if (SharedNodesUtils.isNodeReferencedByLanduse(node)
                    || SharedNodesUtils.isNodeReferencedByAmenityArea(node)) {
                    continue; // landuse and amenity areas stay untouched
                }
                boolean hasInterestingKey = node.getKeys().keySet().stream()
                    .anyMatch(key -> !AbstractPrimitive.isUninterestingKey(key));
                boolean isInRelation = node.getReferrers().stream().anyMatch(Relation.class::isInstance);
                boolean isAttachedToOtherWay = node.getReferrers().stream()
                    .anyMatch(referrer -> referrer instanceof Way
                        && !oldBuildings.contains(referrer)
                        && !referrer.hasKey("building")
                        && !(referrer.hasKey("barrier") && BuildingsSettings.DONT_ATTACH_BARRIER_WAYS.get()));
                if (hasInterestingKey || isInRelation || isAttachedToOtherWay) {
                    nodesToKeep.add(node);
                }
            }
        }
        return new ArrayList<>(nodesToKeep);
    }

    /**
     * Moves the attached nodes (e.g. doors) onto the closest points of the new building outline and
     * inserts them into the outline, so they stay attached to the building instead of being orphaned
     * by the deletion of the old parts. The building is resolved lazily at execution time because it
     * is created by the preceding command of the sequence.
     */
    private static final class AttachNodesToBuildingCommand extends Command {
        private final CommandResultBuilding resultBuilding;
        private final List<Node> nodesToAttach;
        private final List<Command> executedCommands = new ArrayList<>();

        AttachNodesToBuildingCommand(DataSet dataSet, CommandResultBuilding resultBuilding, List<Node> nodesToAttach) {
            super(dataSet);
            this.resultBuilding = resultBuilding;
            this.nodesToAttach = nodesToAttach;
        }

        @Override
        public boolean executeCommand() {
            Way building = resultBuilding.getResultBuilding();
            Map<Node, LatLon> projections = new HashMap<>();
            for (Node node : nodesToAttach) {
                projections.put(node, ReplaceBuildingGeometryCommand.closestPointOnWay(node.getCoor(), building));
            }
            for (Node node : nodesToAttach) {
                MoveCommand moveCommand = new MoveCommand(node, projections.get(node));
                moveCommand.executeCommand();
                executedCommands.add(moveCommand);
            }
            ChangeNodesCommand insertNodesCommand = new ChangeNodesCommand(
                building, ReplaceBuildingGeometryCommand.insertStickyNodes(building, nodesToAttach)
            );
            insertNodesCommand.executeCommand();
            executedCommands.add(insertNodesCommand);
            return true;
        }

        @Override
        public void fillModifiedData(
            Collection<OsmPrimitive> modified,
            Collection<OsmPrimitive> deleted,
            Collection<OsmPrimitive> added
        ) {
            modified.addAll(nodesToAttach);
            Way building = resultBuilding.getResultBuilding();
            if (building != null) {
                modified.add(building);
            }
        }

        @Override
        public void undoCommand() {
            for (int i = executedCommands.size() - 1; i >= 0; i--) {
                executedCommands.get(i).undoCommand();
            }
            executedCommands.clear();
        }

        @Override
        public String getDescriptionText() {
            return tr("Attached the doors to the building");
        }
    }

    /**
     * Deletes the old parts and the untagged nodes which were used only by them. The deletion set is
     * computed at execution time (not at construction time), because the preceding commands of the
     * sequence may reuse some of the old nodes in the new building outline (the new building is
     * snapped to existing nodes) – such nodes must stay in the dataset, otherwise the new building
     * would reference a deleted node and the upload would fail with a data integrity error.
     */
    private static final class DeleteOldPartsCommand extends Command {
        private final List<Way> oldBuildings;
        private DeleteCommand deleteCommand;

        DeleteOldPartsCommand(DataSet dataSet, List<Way> oldBuildings) {
            super(dataSet);
            this.oldBuildings = oldBuildings;
        }

        @Override
        public boolean executeCommand() {
            Set<OsmPrimitive> primitivesToDelete = new LinkedHashSet<>(oldBuildings);
            for (Way oldBuilding : oldBuildings) {
                for (Node node : oldBuilding.getNodes()) {
                    if (node.isTagged()) {
                        continue; // tagged nodes (e.g. doors) are kept and were already attached
                    }
                    boolean referencedElsewhere = node.getReferrers().stream()
                        .anyMatch(referrer -> !oldBuildings.contains(referrer));
                    if (!referencedElsewhere) {
                        primitivesToDelete.add(node);
                    }
                }
            }
            deleteCommand = new DeleteCommand(getAffectedDataSet(), primitivesToDelete);
            return deleteCommand.executeCommand();
        }

        @Override
        public void undoCommand() {
            if (deleteCommand != null) {
                deleteCommand.undoCommand();
            }
        }

        @Override
        public void fillModifiedData(
            Collection<OsmPrimitive> modified,
            Collection<OsmPrimitive> deleted,
            Collection<OsmPrimitive> added
        ) {
            if (deleteCommand != null) {
                deleteCommand.fillModifiedData(modified, deleted, added);
            }
        }

        @Override
        public Collection<? extends OsmPrimitive> getParticipatingPrimitives() {
            return deleteCommand != null ? deleteCommand.getParticipatingPrimitives() : oldBuildings;
        }

        @Override
        public String getDescriptionText() {
            return tr("Delete old parts");
        }
    }
}
