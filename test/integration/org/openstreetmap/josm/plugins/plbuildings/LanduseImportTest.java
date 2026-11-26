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
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.plbuildings.commands.AddBuildingGeometryCommand;
import org.openstreetmap.josm.plugins.plbuildings.commands.ReplaceBuildingGeometryCommand;
import org.openstreetmap.josm.testutils.JOSMTestRules;

/**
 * Checks that a landuse area attached to the building is never touched by the import: its nodes are
 * not moved, merged or removed, even when the building geometry is replaced.
 */
public class LanduseImportTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @Test
    void testLanduseAttachedToBuildingStaysUntouched() {
        DataSet ds = new DataSet();
        Node southWest = addNode(ds, 1, latLon(0, 0));
        Node northWest = addNode(ds, 2, latLon(0.001, 0));
        Node northEast = addNode(ds, 3, latLon(0.001, 0.001));
        Node southEast = addNode(ds, 4, latLon(0, 0.001));
        Way building = addClosedWay(ds, 100, "building", "house", southWest, northWest, northEast, southEast);

        // Landuse area sharing the north-east corner of the building and extending further east.
        Node landuseEastOne = addNode(ds, 5, latLon(0.002, 0.001));
        Node landuseEastTwo = addNode(ds, 6, latLon(0.002, 0.002));
        Node landuseNorth = addNode(ds, 7, latLon(0.001, 0.002));
        Way landuse = addClosedWay(
            ds, 200, "landuse", "residential", northEast, landuseEastOne, landuseEastTwo, landuseNorth
        );

        List<Node> landuseNodesBefore = new ArrayList<>(landuse.getNodes());
        List<LatLon> landuseCoordinatesBefore = new ArrayList<>();
        for (Node node : landuseNodesBefore) {
            landuseCoordinatesBefore.add(node.getCoor());
        }

        AddBuildingGeometryCommand newBuilding = importBuilding(ds, 0.0003);
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(
            ds, building, newBuilding
        );
        boolean replaced = replaceCommand.executeCommand();
        Assertions.assertTrue(replaced);
        Assertions.assertTrue(isBuildingWayValid(building), "building replaced with the new geometry");

        // The landuse way keeps exactly the same nodes at the same positions.
        Assertions.assertEquals(landuseNodesBefore, landuse.getNodes(), "landuse node list unchanged");
        for (int i = 0; i < landuseNodesBefore.size(); i++) {
            Assertions.assertEquals(
                landuseCoordinatesBefore.get(i), landuseNodesBefore.get(i).getCoor(),
                "landuse node not moved"
            );
        }
        Assertions.assertFalse(landuse.isDeleted());
        // The building no longer shares the corner with the landuse, but the landuse itself is intact.
        Assertions.assertFalse(building.containsNode(northEast));
    }

    @Test
    void testLanduseStaysUntouchedWhenBuildingHasDoors() {
        DataSet ds = new DataSet();
        Node southWest = addNode(ds, 11, latLon(0, 0));
        Node northWest = addNode(ds, 12, latLon(0.001, 0));
        Node northEast = addNode(ds, 13, latLon(0.001, 0.001));
        Node door = addNode(ds, 14, latLon(0.001, 0.0005));
        door.put("entrance", "main");
        Node southEast = addNode(ds, 15, latLon(0, 0.001));
        Way building = addClosedWay(ds, 110, "building", "house", southWest, northWest, northEast, door, southEast);

        Node landuseEastOne = addNode(ds, 16, latLon(0.002, 0.001));
        Node landuseEastTwo = addNode(ds, 17, latLon(0.002, 0.002));
        Node landuseNorth = addNode(ds, 18, latLon(0.001, 0.002));
        Way landuse = addClosedWay(
            ds, 210, "landuse", "residential", northEast, landuseEastOne, landuseEastTwo, landuseNorth
        );

        List<Node> landuseNodesBefore = new ArrayList<>(landuse.getNodes());
        List<LatLon> landuseCoordinatesBefore = new ArrayList<>();
        for (Node node : landuseNodesBefore) {
            landuseCoordinatesBefore.add(node.getCoor());
        }

        AddBuildingGeometryCommand newBuilding = importBuilding(ds, 0.0003);
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(
            ds, building, newBuilding
        );
        Assertions.assertTrue(replaceCommand.executeCommand());
        Assertions.assertTrue(isBuildingWayValid(building));

        // The door stays attached to the building...
        Assertions.assertTrue(building.containsNode(door), "door stays in the building outline");
        // ...and the landuse area is untouched.
        Assertions.assertEquals(landuseNodesBefore, landuse.getNodes(), "landuse node list unchanged");
        for (int i = 0; i < landuseNodesBefore.size(); i++) {
            Assertions.assertEquals(
                landuseCoordinatesBefore.get(i), landuseNodesBefore.get(i).getCoor(),
                "landuse node not moved"
            );
        }
        Assertions.assertFalse(building.containsNode(northEast), "the landuse corner is no longer in the building");
    }

    private static Node addNode(DataSet ds, long id, LatLon coor) {
        Node node = new Node(id, 1);
        node.setCoor(coor);
        ds.addPrimitive(node);
        return node;
    }

    private static Way addClosedWay(DataSet ds, long id, String key, String value, Node... nodes) {
        Way way = new Way(id, 1);
        way.put(key, value);
        for (Node node : nodes) {
            way.addNode(node);
        }
        way.addNode(nodes[0]);
        ds.addPrimitive(way);
        return way;
    }

    /**
     * Creates a new building in a separate dataset (shifted by the given offset) and adds it to the
     * main dataset via AddBuildingGeometryCommand.
     *
     * @return the AddBuildingGeometryCommand holding the added building
     */
    private static AddBuildingGeometryCommand importBuilding(DataSet ds, double offset) {
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

        AddBuildingGeometryCommand command = new AddBuildingGeometryCommand(ds, importedBuilding);
        Assertions.assertTrue(command.executeCommand());
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "test", null));
        return command;
    }

    private static LatLon latLon(double lat, double lon) {
        return new LatLon(lat, lon);
    }
}
