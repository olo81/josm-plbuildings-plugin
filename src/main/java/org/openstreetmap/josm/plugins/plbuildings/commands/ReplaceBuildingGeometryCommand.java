package org.openstreetmap.josm.plugins.plbuildings.commands;

import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.MoveCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.AbstractPrimitive;
import org.openstreetmap.josm.data.osm.DataIntegrityProblemException;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.plbuildings.enums.ImportStatus;
import org.openstreetmap.josm.plugins.plbuildings.utils.BarrierWaysSnapper;
import org.openstreetmap.josm.plugins.plbuildings.utils.SharedNodesUtils;
import org.openstreetmap.josm.plugins.utilsplugin2.replacegeometry.ReplaceGeometryCommand;
import org.openstreetmap.josm.plugins.utilsplugin2.replacegeometry.ReplaceGeometryException;
import org.openstreetmap.josm.plugins.utilsplugin2.replacegeometry.ReplaceGeometryUtils;
import org.openstreetmap.josm.tools.Logging;


public class ReplaceBuildingGeometryCommand extends Command implements CommandResultBuilding, CommandWithErrorReason {
    /**
     * Replace the old building geometry with the new one
     */
    private final Way selectedBuilding;
    private final CommandResultBuilding resultNewBuilding;
    private Way newBuilding;

    private final List<Command> geometryCommands = new ArrayList<>();

    private final List<OsmPrimitive> snapPrimitives = new ArrayList<>();

    /**
     * Snapshot of the building's nodes taken before the geometry is replaced. Used to restore the
     * original geometry when the import fails, so the dataset never keeps dangling node references.
     */
    private List<Node> originalBuildingNodes;

    private String executeErrorReason;
    private ImportStatus executeErrorStatus;

    public ReplaceBuildingGeometryCommand(DataSet data, Way selectedBuilding, CommandResultBuilding resultNewBuilding) {
        super(data);
        this.selectedBuilding = selectedBuilding;
        this.resultNewBuilding = resultNewBuilding;
    }

    @Override
    public void fillModifiedData(
        Collection<OsmPrimitive> modified,
        Collection<OsmPrimitive> deleted,
        Collection<OsmPrimitive> added
    ) {
        // Note: this method must only report the modified primitives for conflict detection – it is
        // called by the UndoRedoHandler after the command has been executed and must not run the
        // geometry replacement again.
        modified.add(selectedBuilding);
        modified.addAll(selectedBuilding.getNodes());
        if (newBuilding != null) {
            deleted.add(newBuilding);
        }
    }

    @Override
    public Collection<? extends OsmPrimitive> getParticipatingPrimitives() {
        Collection<OsmPrimitive> primitives = new ArrayList<>();
        if (selectedBuilding != null) {
            primitives.add(selectedBuilding);
            primitives.addAll(selectedBuilding.getNodes()); // Nodes changed with replace geometry
        }
        if (newBuilding != null) {
            primitives.add(newBuilding);
            primitives.addAll(newBuilding.getNodes()); // some nodes can be moved to oldBuilding
        }
        primitives.addAll(snapPrimitives);
        return primitives;
    }

    @Override
    public void undoCommand() {
        undoExecutedGeometryCommands();
        restoreOriginalGeometry();
    }

    @Override
    public boolean executeCommand() {
        this.newBuilding = resultNewBuilding.getResultBuilding();

        try {
            updateGeometry(selectedBuilding, newBuilding);
            Logging.debug("Updated geometry for the selected building: {0}", selectedBuilding);
            return true;
        } catch (Exception | AssertionError exception) {
            handleException(exception);
            return false;
        }
    }

    private void handleException(Throwable exception) {
        // If user cancel conflict window
        if (exception instanceof IllegalArgumentException) {
            executeErrorReason = tr("Building merging canceled!");
            executeErrorStatus = ImportStatus.CANCELED;
            Logging.debug(
                "No building (id: {0}) update, caused: Cancel conflict dialog by user",
                selectedBuilding.getId()
            );
        }
        // If selected building cannot be merged (e.g. connected ways/relation)
        else if (exception instanceof ReplaceGeometryException) {
            executeErrorReason = tr(
                "Cannot merge buildings!"
                    + " The old building might be part of ways or relations,"
                    + " or it is not entirely within the downloaded area."
            );
            executeErrorStatus = ImportStatus.IMPORT_ERROR;
            Logging.debug(
                "No building update (id: {0}), caused: Replacing Geometry from UtilPlugins2 error",
                selectedBuilding.getId()
            );
        }
        // If data integrity like nodes duplicated or first!=last has been somehow broken
        else if (exception instanceof DataIntegrityProblemException) {
            executeErrorReason = tr(
                "Cannot merge buildings! A building was wrongly replaced, and the data is broken!"
                    + " Download the area again to repair the data layer."
            );
            executeErrorStatus = ImportStatus.IMPORT_ERROR;
            Logging.error(
                "No building update (id: {0}), caused: DataIntegrity with replacing error! Building: {1}",
                selectedBuilding.getId(),
                selectedBuilding
            );
        } else {
            executeErrorReason = tr("Cannot merge buildings! Unknown error!");
            executeErrorStatus = ImportStatus.IMPORT_ERROR;
            Logging.error(
                "No building update (id: {0}), caused: Unknown error: {1}",
                selectedBuilding.getId(),
                exception.getMessage()
            );
        }
    }


    /**
     * Main execute command which handle geometry building update.
     *
     * @throws ReplaceGeometryException if selected building cannot be merged (e.g. connected ways/relation)
     * @throws IllegalArgumentException if the replacement command cannot be built
     */
    private void updateGeometry(Way selectedBuilding, Way newBuilding) {
        geometryCommands.clear();
        snapPrimitives.clear();
        originalBuildingNodes = new ArrayList<>(selectedBuilding.getNodes());
        try {
            List<Node> stickyNodes = getStickyNodes(selectedBuilding, newBuilding);
            if (stickyNodes.isEmpty()) {
                ReplaceGeometryCommand replaceGeometryCmd = buildReplaceCommandWithAlignedTags(
                    selectedBuilding, newBuilding
                );
                geometryCommands.add(replaceGeometryCmd);
                if (!replaceGeometryCmd.executeCommand()) {
                    throw new ReplaceGeometryException(tr("Cannot merge buildings!"));
                }
            } else {
                replaceGeometryKeepingStickyNodes(selectedBuilding, newBuilding, stickyNodes);
            }
            BarrierWaysSnapper.snapBarrierWaysOntoOutline(
                getAffectedDataSet(), selectedBuilding, geometryCommands, snapPrimitives
            );
            BarrierWaysSnapper.mergeDuplicateOutlineNodes(
                getAffectedDataSet(), selectedBuilding, geometryCommands, snapPrimitives
            );
            if (!isBuildingWayValid(selectedBuilding)) {
                throw new DataIntegrityProblemException("Wrongly merged building!");
            }
        } catch (RuntimeException | AssertionError exception) {
            // Roll back everything and restore the original geometry to keep the dataset consistent.
            // The utilsplugin2 ReplaceGeometryCommand is a SequenceCommand whose undoCommand() is a
            // no-op when its execution failed halfway, so the snapshot restore is mandatory.
            undoExecutedGeometryCommands();
            restoreOriginalGeometry();
            throw exception;
        }
    }

    private void undoExecutedGeometryCommands() {
        for (int i = geometryCommands.size() - 1; i >= 0; i--) {
            try {
                geometryCommands.get(i).undoCommand();
            } catch (Exception | AssertionError exception) {
                // A failing undo must not abort the rollback of the remaining commands.
                Logging.error("Cannot undo geometry command: {0}", exception.getMessage());
            }
        }
        geometryCommands.clear();
    }

    /**
     * Restores the building's node list to the snapshot taken before the replacement, so the building
     * never keeps a reference to an import node which is going to be removed from the dataset.
     */
    private void restoreOriginalGeometry() {
        if (originalBuildingNodes == null || originalBuildingNodes.equals(selectedBuilding.getNodes())) {
            return;
        }
        selectedBuilding.setNodes(new ArrayList<>(originalBuildingNodes));
    }

    /**
     * Nodes of the old building which have their own tags (e.g. entrance=main), relation memberships
     * or are shared with a barrier way (e.g. a fence attached to the building) and are not part of the new
     * geometry. utilsplugin2 refuses to replace geometry in some of such cases, so they are handled
     * separately: they stay attached to the building and are moved onto the new outline.
     */
    private static List<Node> getStickyNodes(Way selectedBuilding, Way newBuilding) {
        // LinkedHashSet keeps the order and protects against duplicated nodes in the old way.
        Set<Node> stickyNodes = new LinkedHashSet<>();
        for (Node node : selectedBuilding.getNodes()) {
            if (newBuilding.containsNode(node)) {
                continue;
            }
            // Nodes shared with another building must not be moved (it would break the neighbouring building).
            if (isNodeSharedWithOtherBuilding(node, selectedBuilding)) {
                continue;
            }
            // Nodes shared with a landuse area must not be moved or merged – the landuse area
            // has to stay untouched.
            if (SharedNodesUtils.isNodeReferencedByLanduse(node)) {
                continue;
            }
            boolean hasInterestingKey = node.getKeys().keySet().stream()
                .anyMatch(key -> !AbstractPrimitive.isUninterestingKey(key));
            boolean isInRelation = node.getReferrers().stream().anyMatch(Relation.class::isInstance);
            boolean isAttachedToOtherWay = getOtherReferrerWays(node, selectedBuilding).findAny().isPresent();
            if (hasInterestingKey || isInRelation || isAttachedToOtherWay) {
                stickyNodes.add(node);
            }
        }
        return new ArrayList<>(stickyNodes);
    }

    private static Stream<Way> getOtherReferrerWays(Node node, Way selectedBuilding) {
        return node.getReferrers().stream()
            .filter(Way.class::isInstance)
            .map(Way.class::cast)
            .filter(way -> !way.equals(selectedBuilding));
    }

    /**
     * Checks if the node is shared with a way which represents another building.
     */
    private static boolean isNodeSharedWithOtherBuilding(Node node, Way selectedBuilding) {
        return getOtherReferrerWays(node, selectedBuilding)
            .anyMatch(way -> way.hasKey("building") || way.hasKey("building:part"));
    }

    private void replaceGeometryKeepingStickyNodes(
        Way selectedBuilding,
        Way newBuilding,
        List<Node> stickyNodes
    ) throws ReplaceGeometryException, IllegalArgumentException {
        Map<Node, LatLon> projections = projectNodesOntoWay(stickyNodes, newBuilding);
        executeGeometryReplace(selectedBuilding, newBuilding, stickyNodes);
        moveStickyNodesOntoOutline(stickyNodes, projections);

        // Attach the doors back to the outline (next to their new positions).
        ChangeNodesCommand insertStickyNodesCommand = new ChangeNodesCommand(
            selectedBuilding, insertStickyNodes(selectedBuilding, stickyNodes)
        );
        geometryCommands.add(insertStickyNodesCommand);
        insertStickyNodesCommand.executeCommand();
    }

    private void executeGeometryReplace(Way selectedBuilding, Way newBuilding, List<Node> stickyNodes)
        throws ReplaceGeometryException, IllegalArgumentException {
        // Temporarily remove sticky nodes so the geometry can be replaced by utilsplugin2.
        List<Node> nodesWithoutSticky = new ArrayList<>(selectedBuilding.getNodes());
        nodesWithoutSticky.removeAll(stickyNodes);
        ChangeNodesCommand removeStickyNodesCommand = new ChangeNodesCommand(selectedBuilding, nodesWithoutSticky);
        removeStickyNodesCommand.executeCommand();
        geometryCommands.add(removeStickyNodesCommand);

        ReplaceGeometryCommand replaceGeometryCmd;
        try {
            replaceGeometryCmd = buildReplaceCommandWithAlignedTags(selectedBuilding, newBuilding);
        } catch (ReplaceGeometryException | IllegalArgumentException exception) {
            removeStickyNodesCommand.undoCommand();
            geometryCommands.remove(removeStickyNodesCommand);
            throw exception;
        }
        geometryCommands.add(replaceGeometryCmd);
        if (!replaceGeometryCmd.executeCommand()) {
            // utilsplugin2 does not undo the partially executed commands when it returns false, so
            // the caller must treat it as a failure and roll back (restoreOriginalGeometry).
            throw new ReplaceGeometryException(tr("Cannot merge buildings!"));
        }
    }

    /**
     * utilsplugin2 shows its own tag conflict dialog when replacing the geometry of a way whose tags
     * differ from the new way's tags. The plugin resolves the tag conflicts itself afterwards (in
     * UpdateBuildingTagsCommand, with the "remember" option), so the tags of the new way are temporarily
     * aligned with the old way to prevent the extra dialog.
     */
    private static ReplaceGeometryCommand buildReplaceCommandWithAlignedTags(
        Way selectedBuilding,
        Way newBuilding
    ) throws ReplaceGeometryException, IllegalArgumentException {
        Map<String, String> originalKeys = new HashMap<>(newBuilding.getKeys());
        // Log which keys would have conflicted – these could have triggered a second (utilsplugin2)
        // conflict dialog if the tags were not aligned here.
        Set<String> differingKeys = new HashSet<>(originalKeys.keySet());
        differingKeys.addAll(selectedBuilding.getKeys().keySet());
        differingKeys.removeIf(key -> Objects.equals(originalKeys.get(key), selectedBuilding.get(key)));
        Logging.info("PlBuildings replace geometry: aligning differing tag keys {0}", differingKeys);
        newBuilding.setKeys(new HashMap<>(selectedBuilding.getKeys()));
        try {
            ReplaceGeometryCommand replaceGeometryCommand = ReplaceGeometryUtils.buildReplaceWithNewCommand(
                selectedBuilding, newBuilding
            );
            if (replaceGeometryCommand == null) {
                // utilsplugin2 returns null when its tag conflict dialog was canceled by the user.
                throw new ReplaceGeometryException(tr("Cannot merge buildings!"));
            }
            return replaceGeometryCommand;
        } finally {
            newBuilding.setKeys(originalKeys);
        }
    }

    private void moveStickyNodesOntoOutline(List<Node> stickyNodes, Map<Node, LatLon> projections) {
        // Move the doors onto the closest points of the new outline.
        for (Node stickyNode : stickyNodes) {
            MoveCommand moveCommand = new MoveCommand(stickyNode, projections.get(stickyNode));
            geometryCommands.add(moveCommand);
            moveCommand.executeCommand();
        }
    }

    /**
     * Projects sticky nodes onto the nearest points of the new building outline, so the objects attached
     * to the building (doors, cameras, fences) stay as close to their original position as possible.
     *
     * @return mapping of each sticky node to its position on the new building outline
     */
    private static Map<Node, LatLon> projectNodesOntoWay(List<Node> stickyNodes, Way newBuilding) {
        Map<Node, LatLon> projections = new HashMap<>();
        for (Node stickyNode : stickyNodes) {
            projections.put(stickyNode, closestPointOnWay(stickyNode.getCoor(), newBuilding));
        }
        return projections;
    }

    /**
     * Inserts the given nodes into the way next to the segment starts closest to them. Used to keep
     * the doors and other attached nodes in the outline when a building is imported.
     *
     * @return new way node list with the given nodes inserted
     */
    public static List<Node> insertStickyNodes(Way way, List<Node> stickyNodes) {
        List<Node> wayNodes = new ArrayList<>(way.getNodes());
        Map<Node, Integer> insertionIndices = new HashMap<>();
        for (Node stickyNode : stickyNodes) {
            // The node could already be part of the way (e.g. re-added by the geometry replacement).
            if (wayNodes.contains(stickyNode)) {
                continue;
            }
            insertionIndices.put(stickyNode, findInsertionIndex(wayNodes, stickyNode));
        }
        List<Node> nodesToInsert = new ArrayList<>(stickyNodes);
        nodesToInsert.removeIf(node -> !insertionIndices.containsKey(node));
        // Insert from the highest index to keep the computed indices valid. Nodes inserted after the
        // same segment start are ordered by the distance from that start (farthest first), so the
        // final outline keeps them in ascending order instead of backtracking along the segment.
        nodesToInsert.sort(Comparator
            .comparingInt((Node node) -> insertionIndices.get(node))
            .reversed()
            .thenComparing(Comparator.comparingDouble((Node node) -> -BarrierWaysSnapper.distanceSq(
                wayNodes.get(insertionIndices.get(node)).getCoor(), node.getCoor()))));
        for (Node node : nodesToInsert) {
            wayNodes.add(insertionIndices.get(node) + 1, node);
        }
        return wayNodes;
    }

    /**
     * @return index of the way segment start node which is closest to the given node position
     */
    private static int findInsertionIndex(List<Node> wayNodes, Node stickyNode) {
        // When the node's position coincides with an existing outline node, insert directly after
        // that node, so that nodes stacked at the same position stay together in the outline. This
        // keeps later duplicate-node merges from creating shortcuts across the building interior.
        for (int i = 0; i < wayNodes.size() - 1; i++) {
            if (BarrierWaysSnapper.distanceSq(wayNodes.get(i).getCoor(), stickyNode.getCoor())
                < BarrierWaysSnapper.SAME_POSITION_EPSILON_SQ) {
                return i;
            }
        }
        int bestIndex = 0;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < wayNodes.size() - 1; i++) {
            LatLon closest = BarrierWaysSnapper.closestPointOnSegment(
                stickyNode.getCoor(), wayNodes.get(i).getCoor(), wayNodes.get(i + 1).getCoor()
            );
            double distanceSq = BarrierWaysSnapper.distanceSq(stickyNode.getCoor(), closest);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    /**
     * @return the closest point of the way to the given point
     */
    public static LatLon closestPointOnWay(LatLon point, Way way) {
        List<Node> nodes = way.getNodes();
        LatLon closest = point;
        double bestDistanceSq = Double.MAX_VALUE;
        for (int i = 0; i < nodes.size() - 1; i++) {
            LatLon candidate = BarrierWaysSnapper.closestPointOnSegment(
                point, nodes.get(i).getCoor(), nodes.get(i + 1).getCoor()
            );
            double distanceSq = BarrierWaysSnapper.distanceSq(point, candidate);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                closest = candidate;
            }
        }
        return closest;
    }

    @Override
    public String getDescriptionText() {
        return tr("Replaced building geometry");
    }

    @Override
    public Way getResultBuilding() {
        return this.newBuilding;
    }

    @Override
    public String getErrorReason() {
        return executeErrorReason;
    }

    @Override
    public ImportStatus getErrorStatus() {
        return executeErrorStatus;
    }
}
