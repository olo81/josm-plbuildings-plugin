package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

import java.util.Arrays;
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
 * Checks that a building with a tagged node shared with a landuse area (e.g. a camera on the wall)
 * can still be imported: the node stays in place (the landuse area is untouched), while the building
 * gets the new geometry.
 */
public class ProtectedLanduseNodeImportTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @Test
    void testBuildingWithTaggedNodeSharedWithLanduseCanBeImported() {
        DataSet ds = new DataSet();
        // Old building: corners (0,0), (0,0.001), (0.001,0.001), (0.001,0).
        // The positive id marks it as an existing way (utilsplugin2 refuses two new ways).
        Way building = new Way(1234, 1);
        building.put("building", "yes");
        building.put("building:levels", "5");
        Node southWest = addNode(ds, building, latLon(0, 0));
        Node northWest = addNode(ds, building, latLon(0, 0.001));
        Node northEast = addNode(ds, building, latLon(0.001, 0.001));
        Node southEast = addNode(ds, building, latLon(0.001, 0));
        building.addNode(southWest);
        ds.addPrimitive(building);

        // A camera node shared with a landuse area (the landuse way must stay untouched).
        Node camera = new Node(latLon(0, 0.001));
        camera.put("man_made", "surveillance");
        camera.put("camera:type", "fixed");
        ds.addPrimitive(camera);
        Way landuse = new Way();
        landuse.put("landuse", "grass");
        Node landuseEast = new Node(latLon(0.001, 0.002));
        Node landuseWest = new Node(latLon(0, 0.002));
        ds.addPrimitive(landuseEast);
        ds.addPrimitive(landuseWest);
        landuse.setNodes(Arrays.asList(landuseWest, camera, landuseEast, landuseWest));
        ds.addPrimitive(landuse);
        // Attach the camera to the old building as well.
        building.setNodes(Arrays.asList(southWest, northWest, camera, northEast, southEast, southWest));
        building.setModified(true);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "test", null));

        // Imported building with a different geometry.
        DataSet importDs = new DataSet();
        Way importedBuilding = new Way();
        importedBuilding.put("building", "yes");
        importedBuilding.put("building:levels", "5");
        for (Node node : Arrays.asList(
            new Node(latLon(0.0002, -0.0002)),
            new Node(latLon(0.0002, 0.0012)),
            new Node(latLon(0.0012, 0.0012)),
            new Node(latLon(0.0012, -0.0002))
        )) {
            importDs.addPrimitive(node);
            importedBuilding.addNode(node);
        }
        importedBuilding.addNode(importedBuilding.getNode(0));
        importDs.addPrimitive(importedBuilding);

        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding);
        addCommand.executeCommand();
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(ds, building, addCommand);
        Assertions.assertTrue(replaceCommand.executeCommand());

        // The building got the new geometry and is valid.
        Assertions.assertTrue(isBuildingWayValid(building));
        Assertions.assertFalse(building.containsNode(camera));

        // The camera node is untouched: alive, in the landuse way, at the original position.
        Assertions.assertFalse(camera.isDeleted());
        Assertions.assertTrue(landuse.containsNode(camera));
        Assertions.assertEquals(latLon(0, 0.001), camera.getCoor());
        Assertions.assertTrue(camera.hasTag("man_made", "surveillance"));
        // The landuse way is unchanged.
        Assertions.assertEquals(4, landuse.getNodesCount());
        Assertions.assertTrue(landuse.containsNode(landuseEast));
        Assertions.assertTrue(landuse.containsNode(landuseWest));
    }

    private static Node addNode(DataSet ds, Way way, LatLon coor) {
        Node node = new Node(coor);
        ds.addPrimitive(node);
        way.addNode(node);
        return node;
    }

    private static LatLon latLon(double lat, double lon) {
        return new LatLon(lat, lon);
    }
}
