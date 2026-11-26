package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.ImportUtils.importOsmFile;
import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitiveType;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.plbuildings.commands.AddBuildingGeometryCommand;
import org.openstreetmap.josm.plugins.plbuildings.commands.ReplaceBuildingGeometryCommand;
import org.openstreetmap.josm.testutils.JOSMTestRules;

/**
 * Checks that objects attached to the building (doors, camera, fences, power line) stay attached
 * and are moved onto the new outline when the building geometry is replaced.
 */
public class StickyNodesImportTest {
    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @Test
    void testAttachedObjectsFollowTheNewOutline() {
        DataSet ds = importOsmFile(new File("test/data/sticky_nodes/current_building.osm"), "");
        Assertions.assertNotNull(ds);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "test", null));

        DataSet importDs = importOsmFile(new File("test/data/sticky_nodes/import_building.osm"), "");
        Assertions.assertNotNull(importDs);
        Way importedBuilding = (Way) importDs.getWays().toArray()[0];

        Way selectedBuilding = ds.getWays().stream()
            .filter(way -> way.hasTag("building"))
            .findFirst()
            .orElseThrow();
        List<Node> stickyNodes = List.of(
            getNode(ds, 101952), // door 1 (entrance=main)
            getNode(ds, 101956), // door 2 (entrance=yes)
            getNode(ds, 101954), // camera
            getNode(ds, 101953), // fence 1 attachment
            getNode(ds, 101957), // fence 2 attachment
            getNode(ds, 101951)  // power line attachment
        );

        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding);
        addCommand.executeCommand();

        // The new outline is captured now – the new way is deleted by the replacement later.
        List<LatLon> newOutline = new ArrayList<>();
        for (Node node : addCommand.getResultBuilding().getNodes()) {
            newOutline.add(node.getCoor());
        }

        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(
            ds, selectedBuilding, addCommand
        );
        Assertions.assertTrue(replaceCommand.executeCommand());
        Assertions.assertTrue(isBuildingWayValid(selectedBuilding));

        // All attached objects remain members of the building outline (or are merged into a node
        // which already lies at the same position).
        for (Node stickyNode : stickyNodes) {
            if (stickyNode.isDeleted()) {
                continue;
            }
            Assertions.assertTrue(selectedBuilding.containsNode(stickyNode), "node " + stickyNode.getId());
        }

        // And they are positioned on (or very close to) the new outline.
        for (Node stickyNode : stickyNodes) {
            if (stickyNode.isDeleted()) {
                continue;
            }
            Assertions.assertTrue(
                minDistanceToOutline(stickyNode.getCoor(), newOutline) < 1e-6,
                "node " + stickyNode.getId() + " is not on the new outline"
            );
        }

        // Attached ways still share their nodes with the building. Nodes stacked at the same position
        // are merged: the untagged fence/power attachment nodes are merged into the tagged door nodes.
        Way fenceOne = (Way) ds.getPrimitiveById(102476, OsmPrimitiveType.WAY);
        Way fenceTwo = (Way) ds.getPrimitiveById(102477, OsmPrimitiveType.WAY);
        Way powerLine = (Way) ds.getPrimitiveById(102478, OsmPrimitiveType.WAY);
        Assertions.assertEquals(101956, fenceTwo.firstNode().getId());
        Assertions.assertEquals(101952, powerLine.firstNode().getId());
        Assertions.assertTrue(getNode(ds, 101957).isDeleted());
        Assertions.assertTrue(getNode(ds, 101951).isDeleted());

        // Attached ways must not cross the new building walls (e.g. a fence cutting through the building).
        assertNoOutlineCrossing(fenceOne, newOutline);
        assertNoOutlineCrossing(fenceTwo, newOutline);
        assertNoOutlineCrossing(powerLine, newOutline);

        // The fence attached at the old north-east corner is cut at the new east wall: its old attachment
        // node stays only in the building and the fence starts at the shared wall node.
        Assertions.assertEquals(3, fenceOne.getNodesCount());
        Assertions.assertFalse(fenceOne.containsNode(getNode(ds, 101953)));
        Assertions.assertTrue(selectedBuilding.containsNode(fenceOne.firstNode()));
        Assertions.assertTrue(fenceOne.containsNode(getNode(ds, 101958)));
        Assertions.assertTrue(fenceOne.containsNode(getNode(ds, 101959)));

        // A fence which is not connected to the building (no shared nodes) but crosses the new outline
        // is cut at the walls: it is split into two fences whose ends are shared with the outline.
        Way crossingFence = (Way) ds.getPrimitiveById(102479, OsmPrimitiveType.WAY);
        Assertions.assertEquals(101963, crossingFence.firstNode().getId());
        Assertions.assertEquals(2, crossingFence.getNodesCount());
        assertNoOutlineCrossing(crossingFence, newOutline);
        Node firstCrossing = crossingFence.lastNode();
        Assertions.assertTrue(selectedBuilding.containsNode(firstCrossing));
        Assertions.assertEquals(50.3392, firstCrossing.getCoor().lat(), 1e-9);
        Assertions.assertEquals(19.48795, firstCrossing.getCoor().lon(), 1e-9);
        Way secondFencePart = null;
        for (Way way : ds.getWays()) {
            if (way.containsNode(getNode(ds, 101964))) {
                secondFencePart = way;
            }
        }
        Assertions.assertNotNull(secondFencePart);
        Assertions.assertEquals(2, secondFencePart.getNodesCount());
        Assertions.assertEquals(101964, secondFencePart.lastNode().getId());
        assertNoOutlineCrossing(secondFencePart, newOutline);
        Assertions.assertTrue(selectedBuilding.containsNode(secondFencePart.firstNode()));
        Assertions.assertEquals(50.3396, secondFencePart.firstNode().getCoor().lat(), 1e-9);
        Assertions.assertEquals(19.48795, secondFencePart.firstNode().getCoor().lon(), 1e-9);

        // A fence which ends inside the building is attached to the wall and its inner node is removed.
        Way endingFence = (Way) ds.getPrimitiveById(102480, OsmPrimitiveType.WAY);
        Assertions.assertEquals(2, endingFence.getNodesCount());
        Assertions.assertEquals(101965, endingFence.firstNode().getId());
        assertNoOutlineCrossing(endingFence, newOutline);
        Assertions.assertTrue(selectedBuilding.containsNode(endingFence.lastNode()));
        Assertions.assertEquals(50.3396, endingFence.lastNode().getCoor().lat(), 1e-9);
        Assertions.assertEquals(19.4877, endingFence.lastNode().getCoor().lon(), 1e-9);
        Node removedInnerNode = (Node) ds.getPrimitiveById(101966, OsmPrimitiveType.NODE);
        Assertions.assertNotNull(removedInnerNode);
        Assertions.assertTrue(removedInnerNode.isDeleted());

        // A fence attached to the building at two points and passing through the interior lies on the
        // building – it is removed (instead of re-routing it along the walls, which would create a
        // line duplicating the outline). Its attachment nodes stay in the building.
        Way attachedThroughFence = (Way) ds.getPrimitiveById(102481, OsmPrimitiveType.WAY);
        Assertions.assertTrue(attachedThroughFence.isDeleted());
        Assertions.assertTrue(selectedBuilding.containsNode(getNode(ds, 101952)));
        Assertions.assertTrue(selectedBuilding.containsNode(getNode(ds, 101956)));
        Node throughInnerNode = (Node) ds.getPrimitiveById(101967, OsmPrimitiveType.NODE);
        Assertions.assertNotNull(throughInnerNode);
        Assertions.assertTrue(throughInnerNode.isDeleted());

        // The validator must not flag duplicated nodes after the import.
        Assertions.assertTrue(
            findDuplicateNodeErrors(ds).isEmpty(),
            "duplicate nodes after import: " + findDuplicateNodeErrors(ds)
        );
        Assertions.assertTrue(findDuplicatedWayNodesErrors(ds).isEmpty(), "duplicated way nodes after import");

        // Undo restores the original building geometry with all attached objects.
        replaceCommand.undoCommand();
        Assertions.assertTrue(isBuildingWayValid(selectedBuilding));
        for (Node stickyNode : stickyNodes) {
            Assertions.assertTrue(selectedBuilding.containsNode(stickyNode), "node " + stickyNode.getId());
        }
        Assertions.assertTrue(findDuplicateNodeErrors(ds).isEmpty(), "duplicate nodes after undo");
        // And the snapped fences are restored to their original state.
        crossingFence = (Way) ds.getPrimitiveById(102479, OsmPrimitiveType.WAY);
        Assertions.assertEquals(2, crossingFence.getNodesCount());
        Assertions.assertTrue(crossingFence.containsNode(getNode(ds, 101964)));
        Assertions.assertNull(ds.getPrimitiveById(secondFencePart.getUniqueId(), OsmPrimitiveType.WAY));
        endingFence = (Way) ds.getPrimitiveById(102480, OsmPrimitiveType.WAY);
        Assertions.assertEquals(2, endingFence.getNodesCount());
        Node restoredInnerNode = (Node) ds.getPrimitiveById(101966, OsmPrimitiveType.NODE);
        Assertions.assertNotNull(restoredInnerNode);
        Assertions.assertFalse(restoredInnerNode.isDeleted());
        Assertions.assertTrue(endingFence.containsNode(restoredInnerNode));
        // And the attached-through fence is restored with its inner node.
        attachedThroughFence = (Way) ds.getPrimitiveById(102481, OsmPrimitiveType.WAY);
        Assertions.assertEquals(3, attachedThroughFence.getNodesCount());
        Node restoredThroughNode = (Node) ds.getPrimitiveById(101967, OsmPrimitiveType.NODE);
        Assertions.assertNotNull(restoredThroughNode);
        Assertions.assertFalse(restoredThroughNode.isDeleted());
        Assertions.assertTrue(attachedThroughFence.containsNode(restoredThroughNode));
    }

    @Test
    void testNewBuildingImportCutsCrossingFence() {
        DataSet ds = new DataSet();
        Node fenceStart = new Node(new LatLon(50.3394, 19.4870));
        Node fenceEnd = new Node(new LatLon(50.3394, 19.4885));
        Way fence = new Way();
        fence.setNodes(Arrays.asList(fenceStart, fenceEnd));
        fence.put("barrier", "fence");
        ds.addPrimitiveRecursive(fence);

        DataSet importDs = importOsmFile(new File("test/data/sticky_nodes/import_building.osm"), "");
        Assertions.assertNotNull(importDs);
        Way importedBuilding = (Way) importDs.getWays().toArray()[0];

        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding, true);
        Assertions.assertTrue(addCommand.executeCommand());
        Way newBuilding = addCommand.getResultBuilding();

        // The fence (no shared nodes with the building) is cut at the walls: the original way keeps
        // the first part and a new way gets the second part.
        Assertions.assertEquals(fenceStart, fence.firstNode());
        Assertions.assertEquals(2, fence.getNodesCount());
        Assertions.assertTrue(newBuilding.containsNode(fence.lastNode()));

        Way secondPart = null;
        for (Way way : ds.getWays()) {
            if (way.containsNode(fenceEnd)) {
                secondPart = way;
            }
        }
        Assertions.assertNotNull(secondPart);
        Assertions.assertEquals(2, secondPart.getNodesCount());
        Assertions.assertEquals(fenceEnd, secondPart.lastNode());
        Assertions.assertTrue(newBuilding.containsNode(secondPart.firstNode()));

        // The fence no longer crosses the new building outline.
        List<LatLon> outline = new ArrayList<>();
        for (Node node : newBuilding.getNodes()) {
            outline.add(node.getCoor());
        }
        assertNoOutlineCrossing(fence, outline);
        assertNoOutlineCrossing(secondPart, outline);

        // Undo restores the original fence and removes the new way and the building.
        addCommand.undoCommand();
        Assertions.assertEquals(2, fence.getNodesCount());
        Assertions.assertEquals(fenceStart, fence.firstNode());
        Assertions.assertEquals(fenceEnd, fence.lastNode());
        for (Way way : ds.getWays()) {
            if (way != fence) {
                Assertions.assertFalse(way.containsNode(fenceEnd));
            }
        }
    }

    private static Node getNode(DataSet ds, long id) {
        return (Node) ds.getPrimitiveById(id, OsmPrimitiveType.NODE);
    }

    private static java.util.List<TestError> findDuplicateNodeErrors(DataSet ds) {
        org.openstreetmap.josm.data.validation.tests.DuplicateNode test =
            new org.openstreetmap.josm.data.validation.tests.DuplicateNode();
        test.startTest(null);
        for (Node node : ds.getNodes()) {
            test.visit(node);
        }
        test.endTest();
        return test.getErrors();
    }

    private static java.util.List<TestError> findDuplicatedWayNodesErrors(DataSet ds) {
        org.openstreetmap.josm.data.validation.tests.DuplicatedWayNodes test =
            new org.openstreetmap.josm.data.validation.tests.DuplicatedWayNodes();
        test.startTest(null);
        for (Way way : ds.getWays()) {
            test.visit(way);
        }
        test.endTest();
        return test.getErrors();
    }

    private static double minDistanceToOutline(LatLon point, List<LatLon> outline) {
        double min = Double.MAX_VALUE;
        for (int i = 0; i < outline.size() - 1; i++) {
            min = Math.min(min, distanceToSegment(point, outline.get(i), outline.get(i + 1)));
        }
        return min;
    }

    private static double distanceToSegment(LatLon point, LatLon start, LatLon end) {
        double dx = end.lon() - start.lon();
        double dy = end.lat() - start.lat();
        double lengthSq = dx * dx + dy * dy;
        double dot = (point.lon() - start.lon()) * dx + (point.lat() - start.lat()) * dy;
        double t = lengthSq == 0 ? 0 : Math.max(0, Math.min(1, dot / lengthSq));
        double px = start.lon() + t * dx;
        double py = start.lat() + t * dy;
        return Math.sqrt((point.lon() - px) * (point.lon() - px) + (point.lat() - py) * (point.lat() - py));
    }

    private static void assertNoOutlineCrossing(Way attachedWay, List<LatLon> outline) {
        for (int i = 0; i < attachedWay.getNodesCount() - 1; i++) {
            LatLon start = attachedWay.getNode(i).getCoor();
            LatLon end = attachedWay.getNode(i + 1).getCoor();
            for (int j = 0; j < outline.size() - 1; j++) {
                Assertions.assertFalse(
                    segmentsProperlyIntersect(start, end, outline.get(j), outline.get(j + 1)),
                    "way " + attachedWay.getId() + " crosses the building outline"
                );
            }
        }
    }

    private static void assertNoNodeInsideOutline(Way way, List<LatLon> outline) {
        for (Node node : way.getNodes()) {
            Assertions.assertFalse(
                isPointStrictlyInsidePolygon(node.getCoor(), outline),
                "way " + way.getId() + " has a node inside the building outline"
            );
        }
    }

    private static boolean isPointStrictlyInsidePolygon(LatLon point, List<LatLon> outline) {
        for (int i = 0; i < outline.size() - 1; i++) {
            if (distanceToSegment(point, outline.get(i), outline.get(i + 1)) < 1e-9) {
                return false; // on the boundary
            }
        }
        boolean inside = false;
        for (int i = 0, j = outline.size() - 1; i < outline.size(); j = i++) {
            LatLon a = outline.get(j);
            LatLon b = outline.get(i);
            boolean crosses = (a.lat() > point.lat()) != (b.lat() > point.lat())
                && point.lon() < (b.lon() - a.lon()) * (point.lat() - a.lat()) / (b.lat() - a.lat()) + a.lon();
            if (crosses) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static boolean segmentsProperlyIntersect(LatLon p1, LatLon p2, LatLon q1, LatLon q2) {
        double dx = p2.lon() - p1.lon();
        double dy = p2.lat() - p1.lat();
        double ex = q2.lon() - q1.lon();
        double ey = q2.lat() - q1.lat();
        double denominator = dx * ey - dy * ex;
        if (Math.abs(denominator) < 1e-15) {
            return false;
        }
        double t = ((q1.lon() - p1.lon()) * ey - (q1.lat() - p1.lat()) * ex) / denominator;
        double u = ((q1.lon() - p1.lon()) * dy - (q1.lat() - p1.lat()) * dx) / denominator;
        return t > 1e-9 && t < 1 - 1e-9 && u > 1e-9 && u < 1 - 1e-9;
    }
}
