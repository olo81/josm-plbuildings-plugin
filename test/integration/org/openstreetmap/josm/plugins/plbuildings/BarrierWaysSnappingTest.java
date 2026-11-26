package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.data.validation.tests.DuplicateNode;
import org.openstreetmap.josm.plugins.plbuildings.commands.AddBuildingGeometryCommand;
import org.openstreetmap.josm.testutils.JOSMTestRules;

/**
 * Checks how barrier ways (fences) are handled when a new building is imported on top of them:
 * fences crossing the building are cut at the walls, parts lying on/inside the building are removed
 * and no duplicated lines or duplicated nodes are left behind.
 */
public class BarrierWaysSnappingTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().projection();

    @Test
    void testFenceWithNodesOnWallsIsRemoved() {
        DataSet ds = new DataSet();
        Way fence = addWay(ds, "barrier", "fence", latLon(0.0005, 0), latLon(0.0005, 0.0005), latLon(0.0005, 0.001));

        Way building = importBuilding(ds);

        Assertions.assertTrue(fence.isDeleted(), "fence with nodes on the walls must be removed");
        assertBuildingValid(building);
        assertNoFenceCrossesBuilding(ds, building);
    }

    @Test
    void testFenceLyingAlongWallIsRemoved() {
        DataSet ds = new DataSet();
        Way fence = addWay(ds, "barrier", "fence", latLon(0.0002, 0), latLon(0.0007, 0));

        Way building = importBuilding(ds);

        Assertions.assertTrue(fence.isDeleted(), "fence duplicating the wall must be removed");
        assertBuildingValid(building);
        assertNoFenceCrossesBuilding(ds, building);
    }

    @Test
    void testTwoFencesCrossingAtSamePointShareOneNode() {
        DataSet ds = new DataSet();
        Way fenceOne = addWay(
            ds, "barrier", "fence", latLon(0.0005, -0.0001), latLon(0.0005, 0.0005), latLon(0.0005, 0.0011)
        );
        Way fenceTwo = addWay(
            ds, "barrier", "fence", latLon(0.0005, -0.0002), latLon(0.0005, 0.0006), latLon(0.0007, 0.0011)
        );

        Way building = importBuilding(ds);

        // Both fences cross the west wall at exactly the same point – only one node may exist there.
        Assertions.assertEquals(1, countNodesAt(ds, latLon(0.0005, 0)));
        Assertions.assertFalse(fenceOne.isDeleted());
        Assertions.assertFalse(fenceTwo.isDeleted());
        assertBuildingValid(building);
        assertNoFenceCrossesBuilding(ds, building);
        Assertions.assertTrue(findDuplicateNodeErrors(ds).isEmpty());
    }

    @Test
    void testFenceEndingInsideBuildingIsCutAtWall() {
        DataSet ds = new DataSet();
        Way fence = addWay(
            ds, "barrier", "fence", latLon(0.0005, -0.0004), latLon(0.0005, -0.0001), latLon(0.0005, 0.0006)
        );

        Way building = importBuilding(ds);

        Assertions.assertFalse(fence.isDeleted(), "the outside part of the fence must remain");
        Assertions.assertEquals(3, fence.getNodesCount(), "fence cut at the wall, inside node removed");
        Assertions.assertEquals(0, fence.lastNode().lon(), 1e-9, "fence end lies on the wall");
        assertBuildingValid(building);
        assertNoFenceCrossesBuilding(ds, building);
    }

    @Test
    void testFenceTouchingCornerStaysUnchanged() {
        DataSet ds = new DataSet();
        Way fence = addWay(
            ds, "barrier", "fence", latLon(-0.0003, -0.0003), latLon(0, 0), latLon(-0.0003, 0.0003)
        );
        Node corner = fence.getNode(1);

        Way building = importBuilding(ds);

        Assertions.assertFalse(fence.isDeleted());
        Assertions.assertEquals(3, fence.getNodesCount(), "fence touching the corner stays as one way");
        Assertions.assertTrue(corner.isDeleted(), "the fence node is merged into the building corner node");
        Assertions.assertTrue(building.containsNode(fence.getNode(1)), "corner node is shared with the building");
        assertBuildingValid(building);
        assertNoFenceCrossesBuilding(ds, building);
    }

    @Test
    void testFenceAlongWallThenAwayIsTrimmed() {
        DataSet ds = new DataSet();
        Way fence = addWay(
            ds, "barrier", "fence", latLon(0.0002, 0), latLon(0.0007, 0), latLon(0.0007, -0.0003)
        );
        Node turnNode = fence.getNode(1);

        Way building = importBuilding(ds);

        Assertions.assertFalse(fence.isDeleted());
        Assertions.assertEquals(2, fence.getNodesCount(), "the segment duplicating the wall is removed");
        Assertions.assertSame(turnNode, fence.firstNode(), "fence starts at the point where it leaves the wall");
        Assertions.assertTrue(building.containsNode(turnNode), "fence start is shared with the building");
        assertBuildingValid(building);
        assertNoFenceCrossesBuilding(ds, building);
    }

    @Test
    void testFenceCrossingThroughIsSplit() {
        DataSet ds = new DataSet();
        Way fence = addWay(
            ds, "barrier", "fence", latLon(0.0005, -0.0001), latLon(0.0005, 0.0005), latLon(0.0005, 0.0011)
        );

        Way building = importBuilding(ds);

        Assertions.assertFalse(fence.isDeleted());
        long fenceWayCount = ds.getWays().stream()
            .filter(way -> way.hasTag("barrier", "fence") && !way.isDeleted())
            .count();
        Assertions.assertEquals(2, fenceWayCount, "fence is split into two outside parts");
        assertBuildingValid(building);
        assertNoFenceCrossesBuilding(ds, building);
        Assertions.assertTrue(findDuplicateNodeErrors(ds).isEmpty());
    }

    @Test
    void testDeletedFenceDoesNotBreakNextImport() {
        DataSet ds = new DataSet();
        // A fence lying on the building wall – the first import removes it.
        Way fence = addWay(ds, "barrier", "fence", latLon(0.0002, 0), latLon(0.0007, 0));

        Way buildingOne = importBuildingAt(ds, 0);
        Assertions.assertTrue(fence.isDeleted(), "fence on the building is removed by the first import");
        assertBuildingValid(buildingOne);

        // The deleted fence stays in the dataset (marked as deleted) – the next import of another
        // building must not crash on it and must import the new building successfully.
        Way buildingTwo = importBuildingAt(ds, 0.01);
        Assertions.assertTrue(isBuildingWayValid(buildingTwo));
        Assertions.assertTrue(fence.isDeleted());
        assertBuildingValid(buildingOne);
        assertBuildingValid(buildingTwo);
    }

    private static Way importBuilding(DataSet ds) {
        return importBuildingAt(ds, 0);
    }

    private static Way importBuildingAt(DataSet ds, double offset) {
        DataSet importDs = new DataSet();
        Way importedBuilding = new Way();
        importedBuilding.put("building", "house");
        List<Node> nodes = Arrays.asList(
            new Node(latLon(offset, offset)),
            new Node(latLon(offset + 0.001, offset)),
            new Node(latLon(offset + 0.001, offset + 0.001)),
            new Node(latLon(offset, offset + 0.001))
        );
        for (Node node : nodes) {
            importDs.addPrimitive(node);
            importedBuilding.addNode(node);
        }
        importedBuilding.addNode(nodes.get(0));
        importDs.addPrimitive(importedBuilding);

        AddBuildingGeometryCommand command = new AddBuildingGeometryCommand(ds, importedBuilding, true);
        Assertions.assertTrue(command.executeCommand());
        return command.getResultBuilding();
    }

    private static Way addWay(DataSet ds, String key, String value, LatLon... coordinates) {
        Way way = new Way();
        way.put(key, value);
        for (LatLon coordinate : coordinates) {
            Node node = new Node(coordinate);
            ds.addPrimitive(node);
            way.addNode(node);
        }
        ds.addPrimitive(way);
        return way;
    }

    private static LatLon latLon(double lat, double lon) {
        return new LatLon(lat, lon);
    }

    private static void assertBuildingValid(Way building) {
        Assertions.assertTrue(isBuildingWayValid(building), "building must stay a valid closed way");
    }

    private static int countNodesAt(DataSet ds, LatLon coordinate) {
        int count = 0;
        for (Node node : ds.getNodes()) {
            if (!node.isDeleted()
                && Math.abs(node.lat() - coordinate.lat()) < 1e-9
                && Math.abs(node.lon() - coordinate.lon()) < 1e-9) {
                count++;
            }
        }
        return count;
    }

    private static void assertNoFenceCrossesBuilding(DataSet ds, Way building) {
        List<LatLon> outline = new ArrayList<>();
        for (Node node : building.getNodes()) {
            outline.add(node.getCoor());
        }
        for (Way way : ds.getWays()) {
            if (way.isDeleted() || way.equals(building) || !way.hasKey("barrier")) {
                continue;
            }
            for (int i = 0; i < way.getNodesCount() - 1; i++) {
                for (int j = 0; j < outline.size() - 1; j++) {
                    Assertions.assertFalse(
                        segmentsProperlyIntersect(
                            way.getNode(i).getCoor(), way.getNode(i + 1).getCoor(),
                            outline.get(j), outline.get(j + 1)
                        ),
                        "fence segment must not cross the building wall"
                    );
                }
            }
            for (Node node : way.getNodes()) {
                if (!building.containsNode(node)) {
                    Assertions.assertFalse(
                        isPointStrictlyInsidePolygon(node.getCoor(), outline),
                        "no fence node may lie inside the building"
                    );
                }
            }
        }
    }

    private static boolean segmentsProperlyIntersect(LatLon a, LatLon b, LatLon c, LatLon d) {
        double dx = b.lon() - a.lon();
        double dy = b.lat() - a.lat();
        double ex = d.lon() - c.lon();
        double ey = d.lat() - c.lat();
        double denominator = dx * ey - dy * ex;
        if (Math.abs(denominator) < 1e-15) {
            return false;
        }
        double t = ((c.lon() - a.lon()) * ey - (c.lat() - a.lat()) * ex) / denominator;
        double u = ((c.lon() - a.lon()) * dy - (c.lat() - a.lat()) * dx) / denominator;
        return t > 1e-9 && t < 1 - 1e-9 && u > 1e-9 && u < 1 - 1e-9;
    }

    private static boolean isPointStrictlyInsidePolygon(LatLon point, List<LatLon> polygon) {
        boolean inside = false;
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            LatLon a = polygon.get(j);
            LatLon b = polygon.get(i);
            boolean crossesRay = (a.lat() > point.lat()) != (b.lat() > point.lat())
                && point.lon() < (b.lon() - a.lon()) * (point.lat() - a.lat()) / (b.lat() - a.lat()) + a.lon();
            if (crossesRay) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static List<TestError> findDuplicateNodeErrors(DataSet ds) {
        DuplicateNode test = new DuplicateNode();
        test.startTest(null);
        for (Node node : ds.getNodes()) {
            test.visit(node);
        }
        test.endTest();
        return test.getErrors();
    }
}
