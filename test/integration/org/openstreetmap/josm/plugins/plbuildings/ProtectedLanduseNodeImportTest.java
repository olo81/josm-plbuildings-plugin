package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

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
 * Checks the handling of tagged building nodes shared with other ways:
 * - a node shared with a landuse area (e.g. a camera) stays on the building and the landuse way is
 *   detached from it,
 * - a node shared with an amenity area is left in place (the amenity area is untouched),
 * - when the "do not attach fences" option is enabled, fences are not snapped, cut or deleted.
 */
public class ProtectedLanduseNodeImportTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @Test
    void testCameraStaysOnBuildingAndLanduseIsDetached() {
        DataSet ds = new DataSet();
        // Old building: corners (0,0), (0,0.001), (0.001,0.001), (0.001,0).
        Way building = new Way(1234, 1);
        building.put("building", "yes");
        Node southWest = addNode(ds, building, latLon(0, 0));
        Node northWest = addNode(ds, building, latLon(0, 0.001));
        Node northEast = addNode(ds, building, latLon(0.001, 0.001));
        Node southEast = addNode(ds, building, latLon(0.001, 0));
        building.addNode(southWest);
        ds.addPrimitive(building);

        // A camera node shared with a landuse area.
        Node camera = new Node(latLon(0, 0.001));
        camera.put("man_made", "surveillance");
        camera.put("camera:type", "fixed");
        ds.addPrimitive(camera);
        Way landuse = new Way(5678, 1);
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
        Way importedBuilding = createImportedBuilding();
        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding);
        addCommand.executeCommand();
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(ds, building, addCommand);
        Assertions.assertTrue(replaceCommand.executeCommand());

        // The building got the new geometry and the camera stayed attached to it.
        Assertions.assertTrue(isBuildingWayValid(building));
        Assertions.assertTrue(building.containsNode(camera));
        Assertions.assertTrue(camera.hasTag("man_made", "surveillance"));

        // The landuse way was detached from the camera but stays otherwise intact and closed.
        Assertions.assertFalse(landuse.containsNode(camera));
        Assertions.assertTrue(landuse.isClosed());
        Assertions.assertTrue(landuse.containsNode(landuseEast));
        Assertions.assertTrue(landuse.containsNode(landuseWest));
        Assertions.assertFalse(landuse.isDeleted());
    }

    @Test
    void testTaggedNodeSharedWithAmenityAreaStaysUntouched() {
        DataSet ds = new DataSet();
        Way building = new Way(1234, 1);
        building.put("building", "yes");
        Node southWest = addNode(ds, building, latLon(0, 0));
        Node northWest = addNode(ds, building, latLon(0, 0.001));
        Node northEast = addNode(ds, building, latLon(0.001, 0.001));
        Node southEast = addNode(ds, building, latLon(0.001, 0));
        building.addNode(southWest);
        ds.addPrimitive(building);

        // An entrance node shared with an amenity area (e.g. a school yard).
        Node entrance = new Node(latLon(0, 0.001));
        entrance.put("entrance", "main");
        ds.addPrimitive(entrance);
        Way amenityArea = new Way(5678, 1);
        amenityArea.put("amenity", "kindergarten");
        Node areaEast = new Node(latLon(0.001, 0.002));
        Node areaWest = new Node(latLon(0, 0.002));
        ds.addPrimitive(areaEast);
        ds.addPrimitive(areaWest);
        amenityArea.setNodes(Arrays.asList(areaWest, entrance, areaEast, areaWest));
        ds.addPrimitive(amenityArea);
        building.setNodes(Arrays.asList(southWest, northWest, entrance, northEast, southEast, southWest));
        building.setModified(true);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "test", null));

        Way importedBuilding = createImportedBuilding();
        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding);
        addCommand.executeCommand();
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(ds, building, addCommand);
        Assertions.assertTrue(replaceCommand.executeCommand());

        // The entrance node stays in place, in the amenity area – the amenity area is untouched.
        Assertions.assertFalse(entrance.isDeleted());
        Assertions.assertTrue(amenityArea.containsNode(entrance));
        Assertions.assertEquals(latLon(0, 0.001), entrance.getCoor());
        Assertions.assertTrue(amenityArea.isClosed());
        Assertions.assertFalse(amenityArea.isDeleted());
    }

    @Test
    void testFencesUntouchedWhenOptionEnabled() {
        DataSet ds = new DataSet();
        Way building = new Way(1234, 1);
        building.put("building", "yes");
        Node southWest = addNode(ds, building, latLon(0, 0));
        Node northWest = addNode(ds, building, latLon(0, 0.001));
        Node northEast = addNode(ds, building, latLon(0.001, 0.001));
        Node southEast = addNode(ds, building, latLon(0.001, 0));
        building.addNode(southWest);
        ds.addPrimitive(building);

        // A fence crossing the new outline.
        Way fence = new Way(5678, 1);
        fence.put("barrier", "fence");
        Node fenceWest = addNode(ds, fence, latLon(-0.001, 0.0005));
        Node fenceEast = addNode(ds, fence, latLon(0.002, 0.0005));
        List<Node> fenceNodes = List.of(fenceWest, fenceEast);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "test", null));

        Way importedBuilding = createImportedBuilding();
        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding, true);
        try {
            BuildingsSettings.DONT_ATTACH_BARRIER_WAYS.put(true);
            addCommand.executeCommand();
        } finally {
            BuildingsSettings.DONT_ATTACH_BARRIER_WAYS.put(false);
        }

        // The fence is completely untouched.
        Assertions.assertFalse(fence.isDeleted());
        Assertions.assertEquals(fenceNodes, fence.getNodes());
        Assertions.assertFalse(fenceWest.isDeleted());
        Assertions.assertFalse(fenceEast.isDeleted());
    }

    private static Way createImportedBuilding() {
        DataSet importDs = new DataSet();
        Way importedBuilding = new Way();
        importedBuilding.put("building", "yes");
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
        return importedBuilding;
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
