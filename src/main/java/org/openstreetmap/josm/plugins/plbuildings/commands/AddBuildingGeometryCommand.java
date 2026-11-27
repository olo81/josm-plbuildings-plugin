package org.openstreetmap.josm.plugins.plbuildings.commands;

import static org.openstreetmap.josm.plugins.plbuildings.utils.SharedNodesUtils.getBbox;
import static org.openstreetmap.josm.plugins.plbuildings.utils.SharedNodesUtils.isCloseNode;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.plbuildings.BuildingsSettings;
import org.openstreetmap.josm.plugins.plbuildings.enums.ImportStatus;
import org.openstreetmap.josm.plugins.plbuildings.utils.BarrierWaysSnapper;
import org.openstreetmap.josm.plugins.plbuildings.utils.SharedNodesUtils;
import org.openstreetmap.josm.tools.Logging;


/**
 * Add building geometry to dataset and merge with existing nodes.
 * It could not copy all new nodes. It will create only the new which cannot be reused.
 */
public class AddBuildingGeometryCommand extends Command implements CommandResultBuilding, CommandWithErrorReason {

    private final Way importedBuilding; // raw new building with duplicated nodes etc.
    private final DataSet dataSet;
    private final boolean snapBarrierWays;

    /**
     * Only missing nodes (without existing nodes – not all of created building nodes).
     */
    private List<Node> createdNodes;
    private Way createdBuilding;

    private final List<Command> fenceCommands = new ArrayList<>();
    private final List<OsmPrimitive> fencePrimitives = new ArrayList<>();

    private String executeErrorReason;
    private ImportStatus executeErrorStatus;

    public AddBuildingGeometryCommand(DataSet dataSet, Way importedBuilding) {
        this(dataSet, importedBuilding, false);
    }

    /**
     * Creates the command. Set snapBarrierWays to true only when the added building is the final
     * result of the import (a new building) so that fences crossing it are cut. When the geometry is
     * going to be replaced afterwards, the replacing command handles the fences instead.
     *
     * @param snapBarrierWays whether to cut fences crossing the added building
     */
    public AddBuildingGeometryCommand(DataSet dataSet, Way importedBuilding, boolean snapBarrierWays) {
        super(dataSet);
        this.dataSet = dataSet;
        this.importedBuilding = importedBuilding;
        this.snapBarrierWays = snapBarrierWays;
    }

    @Override
    public void fillModifiedData(
        Collection<OsmPrimitive> modified,
        Collection<OsmPrimitive> deleted,
        Collection<OsmPrimitive> added
    ) {
        added.add((OsmPrimitive) createdNodes);
        added.add(createdBuilding);
    }

    @Override
    public Collection<? extends OsmPrimitive> getParticipatingPrimitives() {
        List<OsmPrimitive> participatingPrimitives = new ArrayList<>();
        if (createdBuilding != null) {
            participatingPrimitives.addAll(createdBuilding.getNodes());
            participatingPrimitives.add(createdBuilding);
        }
        participatingPrimitives.addAll(fencePrimitives);

        return participatingPrimitives;
    }

    @Override
    public void undoCommand() {
        for (int i = fenceCommands.size() - 1; i >= 0; i--) {
            try {
                fenceCommands.get(i).undoCommand();
            } catch (Exception | AssertionError exception) {
                // A failing undo must not abort the rollback of the remaining commands.
                Logging.error("Cannot undo fence command: {0}", exception.getMessage());
            }
        }
        detachCreatedNodesFromOtherWays();
        removeCreatedPrimitives();
    }

    /**
     * Defensive rollback: when a following geometry replacement failed and was not fully rolled back,
     * the replaced building may still reference the import nodes. They are detached here before being
     * removed from the dataset, so that no way is left with a dangling node reference.
     */
    private void detachCreatedNodesFromOtherWays() {
        if (createdNodes == null) {
            return;
        }
        for (Node node : createdNodes) {
            if (node.getDataSet() == null) {
                continue;
            }
            List<Way> referringWays = new ArrayList<>();
            for (OsmPrimitive referrer : node.getReferrers()) {
                if (referrer instanceof Way && !referrer.equals(createdBuilding)) {
                    referringWays.add((Way) referrer);
                }
            }
            for (Way way : referringWays) {
                List<Node> nodes = new ArrayList<>(way.getNodes());
                nodes.removeIf(current -> current == node);
                if (!nodes.equals(way.getNodes())) {
                    way.setNodes(nodes);
                    // setNodes alone does not mark the way as modified – without this the changed
                    // way would not be uploaded and the server would reject the node removal.
                    way.setModified(true);
                }
            }
        }
    }

    private void removeCreatedPrimitives() {
        if (createdBuilding != null && createdBuilding.getDataSet() != null) {
            dataSet.removePrimitive(createdBuilding);
        }
        if (createdNodes == null) {
            return;
        }
        for (Node node : createdNodes) {
            if (node.getDataSet() != null) {
                dataSet.removePrimitive(node);
            }
        }
    }

    @Override
    public boolean executeCommand() {
        if (createdBuilding == null) { // It's necessary for redo handling
            prepareBuilding();
        }
        addBuilding();

        // Cut fences which cross the new building outline (even without shared nodes) and merge nodes
        // which ended up at the same position. Skipped when the user disabled fence attaching.
        if (snapBarrierWays && !BuildingsSettings.DONT_ATTACH_BARRIER_WAYS.get()) {
            fenceCommands.clear();
            fencePrimitives.clear();
            try {
                BarrierWaysSnapper.snapBarrierWaysOntoOutline(dataSet, createdBuilding, fenceCommands, fencePrimitives);
                BarrierWaysSnapper.mergeDuplicateOutlineNodes(dataSet, createdBuilding, fenceCommands, fencePrimitives);
            } catch (RuntimeException | AssertionError exception) {
                // Roll back everything executed so far to keep the dataset consistent.
                Logging.error("Cannot snap barrier ways onto the new building: {0}", exception.getMessage());
                executeErrorReason = tr("Cannot import the building (barrier ways snapping failed)!");
                executeErrorStatus = ImportStatus.IMPORT_ERROR;
                undoCommand();
                return false;
            }
        }

        return true;
    }

    @Override
    public String getErrorReason() {
        return executeErrorReason;
    }

    @Override
    public ImportStatus getErrorStatus() {
        return executeErrorStatus;
    }

    @Override
    public String getDescriptionText() {
        return tr("Added building geometry");
    }

    /**
     * Prepare building data (nodes and way) to add it to the dataset
     * It will check which nodes should be skipped from import and should be reused from already existing nodes
     * it checks for that close nodes with BBOX_OFFSET from BuildingSettings
     * then it will construct a new building object based on above checks
     * and add it missing nodes and building (with existing and new nodes) to dataset.
     */
    private void prepareBuilding() {
        // last node is wrongly duplicated after cloning operation, so skip it now
        // and add first node to the way below to get it as closed line (area)
        List<Node> newNodes = importedBuilding.getNodes().stream()
            .limit(importedBuilding.getNodesCount() - 1)
            .map(node -> new Node(node, true))
            .collect(Collectors.toList());

        Way newBuilding = new Way();

        // Check if any building's node is very close to existing node – almost/same lat lon and replace it
        BBox bbox = getBbox(newNodes, BuildingsSettings.BBOX_OFFSET.get());

        List<Node> closeNodes = dataSet.searchNodes(bbox).stream()
            .filter(n -> !n.isDeleted())
            .filter(SharedNodesUtils::isShareableNode)
            .collect(Collectors.toList());

        LinkedHashSet<Node> buildingNodes = new LinkedHashSet<>();
        LinkedHashSet<Node> nodesToAddToDataSet = new LinkedHashSet<>();

        newNodes.forEach(newNode -> {
            Node sameNode = closeNodes.stream()
                .filter(closeNode -> isCloseNode(closeNode, newNode, BuildingsSettings.BBOX_OFFSET.get()))
                .findFirst().orElse(null);

            if (sameNode != null) {
                buildingNodes.add(sameNode);
            } else {
                buildingNodes.add(newNode);
                nodesToAddToDataSet.add(newNode);
            }
        });
        newBuilding.setNodes(new ArrayList<>(buildingNodes));
        newBuilding.addNode(newBuilding.getNode(0));

        createdBuilding = newBuilding;
        createdNodes = new ArrayList<>(nodesToAddToDataSet);
    }

    public void addBuilding() {
        createdNodes.forEach(dataSet::addPrimitive);
        dataSet.addPrimitive(createdBuilding);
        Logging.debug("Added new building {0}", createdBuilding.getId());
    }

    @Override
    public Way getResultBuilding() {
        return createdBuilding;
    }
}
