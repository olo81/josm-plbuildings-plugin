package org.openstreetmap.josm.plugins.plbuildings.utils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.command.ChangeNodesCommand;
import org.openstreetmap.josm.command.ChangePropertyCommand;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.DeleteCommand;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.tools.Logging;

/**
 * Cuts barrier ways (e.g. fences) which cross a newly imported building outline – even when they
 * don't share any node with the building. The cut fences are attached to the walls, the parts lying
 * inside the building are removed and fences passing through the building are split into two ways.
 * Also merges nodes which ended up at the same position (JOSM's validator flags such duplicates).
 * Used both when adding a new building and when replacing the geometry of an existing one.
 */
public final class BarrierWaysSnapper {

    private static final double NODE_EPSILON_SQ = 1e-18; // squared distance, ~0.1 m in degrees

    /**
     * Squared distance below which two nodes are treated as lying at the same position.
     */
    public static final double SAME_POSITION_EPSILON_SQ = NODE_EPSILON_SQ;

    /**
     * Squared distance below which an existing node is reused for a crossing point (~1 cm in
     * degrees – the OSM coordinate precision). Without this, a crossing computed a millimetre away
     * from an existing fence node would create a duplicate node and delete the original one.
     */
    private static final double NODE_REUSE_EPSILON_SQ = 1e-14;

    private BarrierWaysSnapper() {
    }

    /**
     * @param dataSet the dataset with the building and the barrier ways
     * @param outline the (new) building outline
     * @param commands executed commands are appended here (for undo)
     * @param touchedPrimitives primitives modified by the executed commands (for conflict detection)
     */
    public static void snapBarrierWaysOntoOutline(
        DataSet dataSet,
        Way outline,
        List<Command> commands,
        Collection<OsmPrimitive> touchedPrimitives
    ) {
        List<Way> barrierWays = new ArrayList<>();
        for (Way way : dataSet.getWays()) {
            // Deleted ways (e.g. fences removed by a previous import) stay in the dataset and must
            // not be processed again – deleting them again would crash the import.
            if (way.isDeleted()) {
                continue;
            }
            if (way.equals(outline) || way.hasKey("building") || way.hasKey("building:part")) {
                continue;
            }
            if (!way.hasKey("barrier") || way.isClosed()) {
                continue;
            }
            if (way.getReferrers().stream().anyMatch(Relation.class::isInstance)) {
                continue;
            }
            barrierWays.add(way);
        }
        // The dataset does not guarantee a stable order of ways – processing barriers in a fixed
        // order makes the result deterministic (e.g. shared crossing nodes and outline updates).
        barrierWays.sort(Comparator.comparingLong(OsmPrimitive::getUniqueId));
        for (Way barrier : barrierWays) {
            snapBarrierWayOntoOutline(dataSet, barrier, outline, commands, touchedPrimitives);
        }
    }

    /**
     * Merges nodes which ended up at the same position (within OSM precision) into one node. The tags
     * and way references are moved onto the survivor node (preferably the tagged one).
     */
    public static void mergeDuplicateOutlineNodes(
        DataSet dataSet,
        Way outline,
        List<Command> commands,
        Collection<OsmPrimitive> touchedPrimitives
    ) {
        Map<LatLon, List<Node>> groups = new HashMap<>();
        for (Node node : outline.getNodes()) {
            LatLon rounded = node.getCoor().getRoundedToOsmPrecision();
            groups.computeIfAbsent(rounded, key -> new ArrayList<>()).add(node);
        }
        for (List<Node> group : groups.values()) {
            if (group.size() > 1) {
                mergeNodeGroup(dataSet, group, commands, touchedPrimitives);
            }
        }
    }

    /**
     * Naive planar projection (like the rest of this plugin uses lat/lon as planar coordinates).
     *
     * @return the closest point on the segment (start, end) to the given point
     */
    public static LatLon closestPointOnSegment(LatLon point, LatLon start, LatLon end) {
        double dx = end.lon() - start.lon();
        double dy = end.lat() - start.lat();
        double lengthSq = dx * dx + dy * dy;
        double t = lengthSq == 0 ? 0 : Math.max(0, Math.min(1, dotProjectionFactor(point, start, dx, dy) / lengthSq));
        return new LatLon(start.lat() + t * dy, start.lon() + t * dx);
    }

    /**
     * @return squared distance between the two points (lat/lon as planar coordinates)
     */
    public static double distanceSq(LatLon first, LatLon second) {
        double dx = first.lon() - second.lon();
        double dy = first.lat() - second.lat();
        return dx * dx + dy * dy;
    }

    private static void snapBarrierWayOntoOutline(
        DataSet dataSet,
        Way barrier,
        Way outline,
        List<Command> commands,
        Collection<OsmPrimitive> touchedPrimitives
    ) {
        if (wayLiesOnOutline(barrier, outline)) {
            // The whole way duplicates a part of the building outline – remove it instead of
            // leaving a line on top of the building.
            deleteBarrierWay(dataSet, barrier, commands, touchedPrimitives);
            return;
        }
        // Merge barrier nodes which lie exactly on an outline node into one node (reuse the node
        // instead of leaving two nodes at the same position). The node which carries more tags is
        // kept (e.g. an entrance node stays and the untagged duplicate is removed).
        for (int i = 0; i < barrier.getNodesCount(); i++) {
            Node node = barrier.getNode(i);
            // A node shared with a landuse area must not be merged (it would rewire the landuse way).
            if (SharedNodesUtils.isNodeReferencedByLanduse(node)) {
                continue;
            }
            for (Node outlineNode : outline.getNodes()) {
                if (outlineNode != node && distanceSq(outlineNode.getCoor(), node.getCoor()) < NODE_EPSILON_SQ) {
                    if (node.getKeys().size() > outlineNode.getKeys().size()) {
                        mergeNodeInto(dataSet, outlineNode, node, commands, touchedPrimitives);
                    } else {
                        mergeNodeInto(dataSet, node, outlineNode, commands, touchedPrimitives);
                    }
                    break;
                }
            }
        }
        List<WayCrossing> crossings = findWayCrossings(barrier, outline);
        for (WayCrossing crossing : crossings) {
            crossing.node = findOrCreateNodeAt(crossing.coordinate, barrier, outline, dataSet);
        }
        List<Node> outlineNodes = computeOutlineWithCrossings(outline, crossings);
        List<Node> removedNodes = new ArrayList<>();
        List<List<Node>> parts = computeNewBarrierParts(barrier, outline, outlineNodes, crossings, removedNodes);
        if (parts == null) {
            return;
        }
        if (parts.isEmpty()) {
            // The whole way lies on or inside the building – remove it.
            deleteBarrierWay(dataSet, barrier, commands, touchedPrimitives);
            return;
        }

        for (WayCrossing crossing : crossings) {
            if (crossing.node.getDataSet() == null) {
                AddCommand addNodeCommand = new AddCommand(dataSet, crossing.node);
                commands.add(addNodeCommand);
                addNodeCommand.executeCommand();
            }
        }
        if (!outlineNodes.equals(outline.getNodes())) {
            ChangeNodesCommand changeOutlineCommand = new ChangeNodesCommand(outline, outlineNodes);
            commands.add(changeOutlineCommand);
            changeOutlineCommand.executeCommand();
        }
        ChangeNodesCommand changeBarrierCommand = new ChangeNodesCommand(barrier, parts.get(0));
        commands.add(changeBarrierCommand);
        changeBarrierCommand.executeCommand();
        touchedPrimitives.add(barrier);
        touchedPrimitives.addAll(barrier.getNodes());
        for (int i = 1; i < parts.size(); i++) {
            Way newWay = new Way();
            newWay.setKeys(barrier.getKeys());
            newWay.setNodes(parts.get(i));
            AddCommand addWayCommand = new AddCommand(dataSet, newWay);
            commands.add(addWayCommand);
            addWayCommand.executeCommand();
            touchedPrimitives.add(newWay);
            touchedPrimitives.addAll(newWay.getNodes());
        }
        for (Node removedNode : new LinkedHashSet<>(removedNodes)) {
            touchedPrimitives.add(removedNode);
            if (!removedNode.isDeleted() && removedNode.getReferrers().isEmpty() && removedNode.getKeys().isEmpty()) {
                DeleteCommand deleteCommand = new DeleteCommand(dataSet, Collections.singleton(removedNode));
                commands.add(deleteCommand);
                deleteCommand.executeCommand();
            }
        }
        Logging.debug("Snapped barrier way {0} onto the building outline {1}", barrier, outline);
    }

    /**
     * Deletes the whole barrier way (it lies on or inside the building) and cleans up its nodes
     * which are no longer referenced and carry no tags.
     */
    private static void deleteBarrierWay(
        DataSet dataSet,
        Way barrier,
        List<Command> commands,
        Collection<OsmPrimitive> touchedPrimitives
    ) {
        if (barrier.isDeleted()) {
            return; // already removed by a previous import
        }
        // Note: DeleteCommand clears the way's node list (resetPrimitiveChildren), so the nodes
        // must be captured before the way is deleted. The command is recorded only after a
        // successful execution so that a failed command is never undone by the rollback.
        final List<Node> barrierNodes = new ArrayList<>(barrier.getNodes());
        DeleteCommand deleteWayCommand = new DeleteCommand(dataSet, Collections.singleton(barrier));
        deleteWayCommand.executeCommand();
        commands.add(deleteWayCommand);
        touchedPrimitives.add(barrier);
        for (Node node : barrierNodes) {
            boolean onlyDeletedReferrers = node.getReferrers().stream().allMatch(OsmPrimitive::isDeleted);
            if (!node.isDeleted() && onlyDeletedReferrers && node.getKeys().isEmpty()) {
                DeleteCommand deleteNodeCommand = new DeleteCommand(dataSet, Collections.singleton(node));
                commands.add(deleteNodeCommand);
                deleteNodeCommand.executeCommand();
                touchedPrimitives.add(node);
            }
        }
        Logging.debug("Deleted barrier way {0} lying on the building", barrier);
    }

    private static List<WayCrossing> findWayCrossings(Way barrier, Way outline) {
        List<WayCrossing> crossings = new ArrayList<>();
        for (int i = 0; i < barrier.getNodesCount() - 1; i++) {
            LatLon a = barrier.getNode(i).getCoor();
            LatLon b = barrier.getNode(i + 1).getCoor();
            for (int j = 0; j < outline.getNodesCount() - 1; j++) {
                LatLon segmentStart = outline.getNode(j).getCoor();
                LatLon segmentEnd = outline.getNode(j + 1).getCoor();
                double[] parameters = segmentIntersectionParameters(a, b, segmentStart, segmentEnd);
                if (parameters == null) {
                    continue;
                }
                double t = parameters[0];
                double u = parameters[1];
                LatLon coordinate = new LatLon(a.lat() + t * (b.lat() - a.lat()), a.lon() + t * (b.lon() - a.lon()));
                crossings.add(new WayCrossing(i, j, t, u, coordinate));
            }
        }
        crossings.sort(
            Comparator.comparingInt((WayCrossing crossing) -> crossing.barrierSegmentIndex)
                .thenComparingDouble(crossing -> crossing.barrierT)
        );
        return crossings;
    }

    /**
     * Finds the intersection of segments (a, b) and (c, d). Crossings at the segment endpoints are
     * detected too (e.g. a fence node lying exactly on a building wall), so that fences whose nodes
     * sit on the outline are cut as well.
     *
     * @return t and u parameters of the intersection or null if the segments do not cross
     */
    private static double[] segmentIntersectionParameters(LatLon a, LatLon b, LatLon c, LatLon d) {
        double dx = b.lon() - a.lon();
        double dy = b.lat() - a.lat();
        double ex = d.lon() - c.lon();
        double ey = d.lat() - c.lat();
        double denominator = dx * ey - dy * ex;
        if (Math.abs(denominator) < 1e-15) {
            return null; // parallel or collinear segments (e.g. a fence lying along the wall)
        }
        double t = ((c.lon() - a.lon()) * ey - (c.lat() - a.lat()) * ex) / denominator;
        double u = ((c.lon() - a.lon()) * dy - (c.lat() - a.lat()) * dx) / denominator;
        double parameterEpsilon = 1e-9;
        if (t > -parameterEpsilon && t < 1 + parameterEpsilon
            && u > -parameterEpsilon && u < 1 + parameterEpsilon) {
            return new double[] {Math.max(0, Math.min(1, t)), Math.max(0, Math.min(1, u))};
        }
        return null;
    }

    private static List<Node> computeOutlineWithCrossings(Way outline, List<WayCrossing> crossings) {
        List<Node> outlineNodes = new ArrayList<>(outline.getNodes());
        List<WayCrossing> crossingsToInsert = new ArrayList<>();
        for (WayCrossing crossing : crossings) {
            if (!outlineNodes.contains(crossing.node)) {
                crossingsToInsert.add(crossing);
            }
        }
        crossingsToInsert.sort(
            Comparator.comparingInt((WayCrossing crossing) -> crossing.outlineSegmentIndex)
                .thenComparingDouble(crossing -> crossing.outlineT)
        );
        // Insert from the last position to keep the earlier indices valid.
        for (int i = crossingsToInsert.size() - 1; i >= 0; i--) {
            WayCrossing crossing = crossingsToInsert.get(i);
            outlineNodes.add(crossing.outlineSegmentIndex + 1, crossing.node);
        }
        return outlineNodes;
    }

    /**
     * Computes the new node list (or lists) for a barrier way crossing the new outline. The way is cut
     * at the walls: the parts lying inside the building and the segments duplicating the walls are
     * removed, the remaining outside parts become separate ways whose ends are shared with the outline.
     * If nothing remains outside, the whole way is removed.
     *
     * @return the list of parts (each with at least 2 distinct nodes), an empty list if the whole way
     *     should be deleted, or null if the way should stay unchanged
     */
    private static List<List<Node>> computeNewBarrierParts(
        Way barrier,
        Way outline,
        List<Node> outlineNodes,
        List<WayCrossing> crossings,
        List<Node> removedNodes
    ) {
        // Build the way node sequence with the crossing nodes inserted at their positions. Each element
        // is marked as boundary (shared with the outline), as lying strictly inside the building, or as
        // lying on a wall segment which duplicates the outline.
        List<Node> elements = new ArrayList<>();
        List<Boolean> isBoundary = new ArrayList<>();
        List<Boolean> isInside = new ArrayList<>();
        int crossingIndex = 0;
        for (int i = 0; i < barrier.getNodesCount(); i++) {
            while (crossingIndex < crossings.size()
                && crossings.get(crossingIndex).barrierSegmentIndex == i - 1) {
                elements.add(crossings.get(crossingIndex).node);
                isBoundary.add(true);
                isInside.add(false);
                crossingIndex++;
            }
            Node node = barrier.getNode(i);
            boolean boundary = outlineNodes.contains(node);
            // A node which is part of a segment duplicating the wall (but not a boundary/transition
            // node) is removed from the fence like an inside node.
            boolean onWallNode = !boundary && isNodeOnOutline(node.getCoor(), outline);
            boolean removableOnWall = onWallNode && (
                (i > 0 && segmentLiesOnOutline(barrier.getNode(i - 1).getCoor(), node.getCoor(), outline))
                    || (i + 1 < barrier.getNodesCount()
                        && segmentLiesOnOutline(node.getCoor(), barrier.getNode(i + 1).getCoor(), outline))
            );
            boolean inside = !boundary && (removableOnWall
                || isPointStrictlyInsidePolygon(node.getCoor(), outline));
            elements.add(node);
            isBoundary.add(boundary);
            isInside.add(inside);
        }
        while (crossingIndex < crossings.size()) {
            elements.add(crossings.get(crossingIndex).node);
            isBoundary.add(true);
            isInside.add(false);
            crossingIndex++;
        }

        // The whole way lies between two points of the building outline (nothing outside): the fence
        // is on/inside the building – remove it instead of re-routing it along the outer walls (which
        // would create a line duplicating the building outline).
        if (elements.size() >= 2 && isBoundary.get(0) && isBoundary.get(elements.size() - 1)) {
            boolean wholeWayInside = elements.size() == 2
                ? isChordInside(elements.get(0), elements.get(elements.size() - 1), outline)
                : allInsideBetween(isInside, 1, elements.size() - 1);
            if (wholeWayInside) {
                for (int i = 1; i < elements.size() - 1; i++) {
                    removedNodes.add(elements.get(i));
                }
                return Collections.emptyList();
            }
        }

        // Cut the way at the walls: remove the inside elements and split at empty inside chords
        // between two boundary nodes.
        List<List<Node>> parts = new ArrayList<>();
        List<Node> current = null;
        int index = 0;
        while (index < elements.size()) {
            if (isInside.get(index)) {
                if (current != null && current.size() >= 2) {
                    parts.add(current);
                }
                current = null;
                while (index < elements.size() && isInside.get(index)) {
                    removedNodes.add(elements.get(index));
                    index++;
                }
                if (index < elements.size()) {
                    current = new ArrayList<>();
                    current.add(elements.get(index));
                    index++;
                }
                continue;
            }
            if (current == null) {
                current = new ArrayList<>();
            }
            current.add(elements.get(index));
            index++;
            if (index < elements.size() && !isInside.get(index) && isBoundary.get(index - 1) && isBoundary.get(index)
                && isChordInside(elements.get(index - 1), elements.get(index), outline)) {
                if (current.size() >= 2) {
                    parts.add(current);
                }
                current = null;
            }
        }
        if (current != null && current.size() >= 2) {
            parts.add(current);
        }

        // Drop degenerate parts (both ends at the same node) and duplicated consecutive nodes –
        // a way must have at least two distinct nodes, otherwise the validator reports duplicates.
        List<List<Node>> validParts = new ArrayList<>();
        for (List<Node> part : parts) {
            List<Node> deduplicated = deduplicateConsecutiveNodes(part);
            if (new LinkedHashSet<>(deduplicated).size() >= 2) {
                validParts.add(deduplicated);
            }
        }
        if (validParts.isEmpty()) {
            // The whole way lies on the building – delete it.
            return Collections.emptyList();
        }
        if (validParts.size() == 1 && validParts.get(0).equals(barrier.getNodes())) {
            return null;
        }
        // Nodes which are no longer part of any fence part (e.g. on-wall nodes of removed segments)
        // can be cleaned up by the caller.
        Set<Node> keptNodes = validParts.stream().flatMap(List::stream).collect(Collectors.toSet());
        for (Node node : barrier.getNodes()) {
            if (!keptNodes.contains(node)) {
                removedNodes.add(node);
            }
        }
        return validParts;
    }

    /**
     * @return true when the point lies on one of the outline segments
     */
    private static boolean isNodeOnOutline(LatLon point, Way outline) {
        List<Node> outlineNodes = outline.getNodes();
        for (int i = 0; i < outlineNodes.size() - 1; i++) {
            LatLon segmentStart = outlineNodes.get(i).getCoor();
            LatLon segmentEnd = outlineNodes.get(i + 1).getCoor();
            if (distanceSq(point, closestPointOnSegment(point, segmentStart, segmentEnd)) < NODE_EPSILON_SQ) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when both segment endpoints lie on the same outline segment (the fence segment
     *     duplicates a part of the building wall)
     */
    private static boolean segmentLiesOnOutline(LatLon start, LatLon end, Way outline) {
        List<Node> outlineNodes = outline.getNodes();
        for (int i = 0; i < outlineNodes.size() - 1; i++) {
            LatLon segmentStart = outlineNodes.get(i).getCoor();
            LatLon segmentEnd = outlineNodes.get(i + 1).getCoor();
            if (distanceSq(start, closestPointOnSegment(start, segmentStart, segmentEnd)) < NODE_EPSILON_SQ
                && distanceSq(end, closestPointOnSegment(end, segmentStart, segmentEnd)) < NODE_EPSILON_SQ) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return true when the whole barrier way lies on the building outline (it duplicates the wall)
     */
    private static boolean wayLiesOnOutline(Way barrier, Way outline) {
        if (barrier.getNodesCount() < 2) {
            return false;
        }
        for (int i = 0; i < barrier.getNodesCount() - 1; i++) {
            if (!segmentLiesOnOutline(barrier.getNode(i).getCoor(), barrier.getNode(i + 1).getCoor(), outline)) {
                return false;
            }
        }
        return true;
    }

    private static boolean allInsideBetween(List<Boolean> isInside, int from, int to) {
        for (int i = from; i < to; i++) {
            if (!isInside.get(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isChordInside(Node first, Node second, Way outline) {
        if (first == second) {
            return false;
        }
        LatLon chordMidpoint = new LatLon(
            (first.getCoor().lat() + second.getCoor().lat()) / 2,
            (first.getCoor().lon() + second.getCoor().lon()) / 2
        );
        return isPointStrictlyInsidePolygon(chordMidpoint, outline);
    }

    private static void mergeNodeGroup(
        DataSet dataSet,
        List<Node> group,
        List<Command> commands,
        Collection<OsmPrimitive> touchedPrimitives
    ) {
        // Prefer the node which carries tags (e.g. a door) as the survivor. Nodes shared with a
        // landuse area are left alone (the landuse area must stay untouched).
        Node survivor = null;
        for (Node candidate : group) {
            if (candidate.getReferrers().stream().anyMatch(Relation.class::isInstance)) {
                continue;
            }
            if (SharedNodesUtils.isNodeReferencedByLanduse(candidate)) {
                continue;
            }
            if (survivor == null || candidate.getKeys().size() > survivor.getKeys().size()) {
                survivor = candidate;
            }
        }
        if (survivor == null) {
            return; // no mergeable nodes in the group
        }
        for (Node node : group) {
            if (node != survivor
                && node.getReferrers().stream().noneMatch(Relation.class::isInstance)
                && !SharedNodesUtils.isNodeReferencedByLanduse(node)) {
                mergeNodeInto(dataSet, node, survivor, commands, touchedPrimitives);
            }
        }
    }

    private static void mergeNodeInto(
        DataSet dataSet,
        Node node,
        Node survivor,
        List<Command> commands,
        Collection<OsmPrimitive> touchedPrimitives
    ) {
        // Move the tags which the survivor doesn't have yet.
        for (Map.Entry<String, String> entry : node.getKeys().entrySet()) {
            if (!survivor.hasKey(entry.getKey())) {
                ChangePropertyCommand tagCommand = new ChangePropertyCommand(
                    survivor, entry.getKey(), entry.getValue()
                );
                commands.add(tagCommand);
                tagCommand.executeCommand();
            }
        }
        // Replace the node with the survivor in all referring ways.
        for (Way way : new ArrayList<>(node.referrers(Way.class).toList())) {
            List<Node> newNodes = replaceNodeInWay(way, node, survivor);
            if (!newNodes.equals(way.getNodes())) {
                ChangeNodesCommand changeNodesCommand = new ChangeNodesCommand(way, newNodes);
                commands.add(changeNodesCommand);
                changeNodesCommand.executeCommand();
            }
            touchedPrimitives.add(way);
            touchedPrimitives.addAll(way.getNodes());
        }
        // The node should be unreferenced now – delete it.
        if (node.getReferrers().isEmpty()) {
            touchedPrimitives.add(node);
            DeleteCommand deleteCommand = new DeleteCommand(dataSet, Collections.singleton(node));
            commands.add(deleteCommand);
            deleteCommand.executeCommand();
        }
    }

    private static List<Node> replaceNodeInWay(Way way, Node oldNode, Node newNode) {
        boolean newNodePresent = way.getNodes().stream().anyMatch(node -> node == newNode);
        List<Node> result = new ArrayList<>();
        Node previous = null;
        for (Node node : way.getNodes()) {
            Node current = node;
            if (node == oldNode) {
                if (newNodePresent) {
                    continue; // the way already contains the merged node – just drop the duplicate
                }
                current = newNode;
            }
            if (current != previous) {
                result.add(current);
                previous = current;
            }
        }
        return result;
    }

    private static List<Node> deduplicateConsecutiveNodes(List<Node> nodes) {
        List<Node> result = new ArrayList<>();
        for (Node node : nodes) {
            if (result.isEmpty() || result.get(result.size() - 1) != node) {
                result.add(node);
            }
        }
        return result;
    }

    private static boolean isPointStrictlyInsidePolygon(LatLon point, Way polygon) {
        List<Node> polygonNodes = polygon.getNodes();
        for (int i = 0; i < polygonNodes.size() - 1; i++) {
            LatLon closest = closestPointOnSegment(
                point, polygonNodes.get(i).getCoor(), polygonNodes.get(i + 1).getCoor()
            );
            if (distanceSq(point, closest) < NODE_EPSILON_SQ) {
                return false; // on the boundary
            }
        }
        boolean inside = false;
        for (int i = 0, j = polygonNodes.size() - 1; i < polygonNodes.size(); j = i++) {
            LatLon a = polygonNodes.get(j).getCoor();
            LatLon b = polygonNodes.get(i).getCoor();
            boolean crossesRay = (a.lat() > point.lat()) != (b.lat() > point.lat())
                && point.lon() < (b.lon() - a.lon()) * (point.lat() - a.lat()) / (b.lat() - a.lat()) + a.lon();
            if (crossesRay) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static Node findOrCreateNodeAt(LatLon coordinate, Way barrier, Way outline, DataSet dataSet) {
        for (Node outlineNode : outline.getNodes()) {
            if (distanceSq(outlineNode.getCoor(), coordinate) < NODE_REUSE_EPSILON_SQ) {
                return outlineNode;
            }
        }
        for (Node barrierNode : barrier.getNodes()) {
            if (distanceSq(barrierNode.getCoor(), coordinate) < NODE_REUSE_EPSILON_SQ) {
                return barrierNode;
            }
        }
        // Reuse an existing node at the same position (e.g. a crossing node created for another
        // barrier way) instead of creating a duplicate node which the validator would flag.
        double searchOffset = 1e-6;
        BBox bbox = new BBox(
            coordinate.lon() - searchOffset, coordinate.lat() - searchOffset,
            coordinate.lon() + searchOffset, coordinate.lat() + searchOffset
        );
        for (Node node : dataSet.searchNodes(bbox)) {
            if (!node.isDeleted() && distanceSq(node.getCoor(), coordinate) < NODE_REUSE_EPSILON_SQ) {
                return node;
            }
        }
        return new Node(coordinate);
    }

    private static double dotProjectionFactor(LatLon point, LatLon start, double dx, double dy) {
        return (point.lon() - start.lon()) * dx + (point.lat() - start.lat()) * dy;
    }

    private static final class WayCrossing {
        private final int barrierSegmentIndex;
        private final int outlineSegmentIndex;
        private final double barrierT;
        private final double outlineT;
        private final LatLon coordinate;
        private Node node;

        WayCrossing(
            int barrierSegmentIndex,
            int outlineSegmentIndex,
            double barrierT,
            double outlineT,
            LatLon coordinate
        ) {
            this.barrierSegmentIndex = barrierSegmentIndex;
            this.outlineSegmentIndex = outlineSegmentIndex;
            this.barrierT = barrierT;
            this.outlineT = outlineT;
            this.coordinate = coordinate;
        }
    }
}
