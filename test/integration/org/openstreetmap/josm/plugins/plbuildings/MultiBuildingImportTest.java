package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.plbuildings.actions.importstrategy.FullImportStrategy;
import org.openstreetmap.josm.plugins.plbuildings.exceptions.ImportActionCanceledException;
import org.openstreetmap.josm.plugins.plbuildings.models.BuildingsImportStats;
import org.openstreetmap.josm.plugins.plbuildings.utils.BarrierWaysSnapper;
import org.openstreetmap.josm.plugins.plbuildings.utils.BuildingsSessionStateManager;
import org.openstreetmap.josm.testutils.JOSMTestRules;

/**
 * Checks that selecting several parts of one block imports a single building replacing the parts
 * and extracts the addresses of the parts as separate address nodes.
 */
public class MultiBuildingImportTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @BeforeEach
    void setUp() {
        BuildingsSessionStateManager.clearTagConflictSessionState();
    }

    @Test
    void testMultipleSelectedPartsAreReplacedByOneBuildingWithAddressNodes() throws ImportActionCanceledException {
        DataSet ds = new DataSet();
        // Three parts of one block (each closed, with its own address).
        Way partOne = addPart(ds, "1", latLon(0, 0), latLon(0, 0.001), latLon(0.001, 0.001), latLon(0.001, 0));
        Way partTwo = addPart(
            ds, "2", latLon(0, 0.001), latLon(0, 0.002), latLon(0.001, 0.002), latLon(0.001, 0.001)
        );
        Way partThree = addPart(
            ds, "3", latLon(0, 0.002), latLon(0, 0.003), latLon(0.001, 0.003), latLon(0.001, 0.002)
        );
        // Part two has a single service (a shop) – it is extracted together with its address into
        // one point. Part three has two services – each gets a separate point.
        partTwo.put("shop", "bakery");
        partTwo.put("name", "Piekarnia Testowa");
        partThree.put("shop", "florist");
        partThree.put("amenity", "post_office");
        // Each part has a door (entrance=main) on its bottom edge – the import must keep the doors
        // attached to the new building outline instead of orphaning or deleting them.
        Node doorOne = addDoor(ds, partOne, latLon(0.0005, 0));
        Node doorTwo = addDoor(ds, partTwo, latLon(0.0005, 0.001));
        Node doorThree = addDoor(ds, partThree, latLon(0.0005, 0.002));
        List<Node> oldPartNodes = new ArrayList<>();
        for (Way part : Arrays.asList(partOne, partTwo, partThree)) {
            oldPartNodes.addAll(part.getNodes());
        }

        // The imported building covers all three parts.
        DataSet importDs = new DataSet();
        Way importedBuilding = new Way();
        importedBuilding.put("building", "yes");
        importedBuilding.put("building:levels", "2");
        List<Node> nodes = Arrays.asList(
            new Node(latLon(0, 0)),
            new Node(latLon(0, 0.003)),
            new Node(latLon(0.001, 0.003)),
            new Node(latLon(0.001, 0))
        );
        for (Node node : nodes) {
            importDs.addPrimitive(node);
            importedBuilding.addNode(node);
        }
        importedBuilding.addNode(nodes.get(0));
        importDs.addPrimitive(importedBuilding);

        BuildingsImportManager manager = BuildingsImportManager.createWithSelectedBuildings(
            ds, latLon(0.0005, 0.0015), Arrays.asList(partOne, partTwo, partThree)
        );
        FullImportStrategy strategy = new FullImportStrategy(
            manager, BuildingsImportStats.getInstance(), importedBuilding
        );
        Way newBuilding = strategy.performImport();

        Assertions.assertNotNull(newBuilding);
        Assertions.assertTrue(isBuildingWayValid(newBuilding));
        Assertions.assertTrue(newBuilding.hasTag("building", "yes"));
        Assertions.assertTrue(newBuilding.hasTag("building:levels", "2"));
        Assertions.assertFalse(newBuilding.hasKey("addr:housenumber"));

        // The old parts are deleted.
        Assertions.assertTrue(partOne.isDeleted());
        Assertions.assertTrue(partTwo.isDeleted());
        Assertions.assertTrue(partThree.isDeleted());

        // Three address nodes with the addresses of the parts.
        List<Node> addressNodes = new ArrayList<>();
        for (Node node : ds.getNodes()) {
            if (node.hasKey("addr:housenumber") && !node.isDeleted()) {
                addressNodes.add(node);
            }
        }
        Assertions.assertEquals(3, addressNodes.size());
        for (Node addressNode : addressNodes) {
            Assertions.assertTrue(addressNode.hasKey("addr:street"));
            // The address node lies inside the new building.
            Assertions.assertTrue(newBuilding.getBBox().contains(addressNode.getCoor()));
        }

        // The single service of part two is merged into its address node (one point).
        Node bakeryNode = findNodeWithTag(ds, "shop", "bakery");
        Assertions.assertNotNull(bakeryNode);
        Assertions.assertFalse(bakeryNode.isDeleted());
        Assertions.assertTrue(bakeryNode.hasTag("addr:housenumber", "2"));
        Assertions.assertTrue(bakeryNode.hasTag("name", "Piekarnia Testowa"));
        Assertions.assertTrue(newBuilding.getBBox().contains(bakeryNode.getCoor()));

        // The two services of part three get one node each, without the address.
        Node floristNode = findNodeWithTag(ds, "shop", "florist");
        Node postOfficeNode = findNodeWithTag(ds, "amenity", "post_office");
        Assertions.assertNotNull(floristNode);
        Assertions.assertNotNull(postOfficeNode);
        Assertions.assertNotSame(floristNode, postOfficeNode);
        Assertions.assertFalse(floristNode.isDeleted());
        Assertions.assertFalse(postOfficeNode.isDeleted());
        Assertions.assertFalse(floristNode.hasKey("addr:housenumber"));

        // The doors stay attached to the new building outline.
        for (Node door : Arrays.asList(doorOne, doorTwo, doorThree)) {
            Assertions.assertFalse(door.isDeleted(), "The door must not be deleted");
            Assertions.assertTrue(
                newBuilding.getNodes().contains(door), "The door must be part of the new building"
            );
            Assertions.assertTrue(isOnOutline(door, newBuilding), "The door must lie on the new building outline");
        }

        // No stray nodes are left over: the tagged doors are in the new building, the untagged
        // corner nodes of the old parts are deleted – except the nodes reused by the new building
        // outline (the import snaps the new geometry to existing nodes), which must stay alive.
        for (Node oldNode : oldPartNodes) {
            if (oldNode.isTagged()) {
                Assertions.assertFalse(oldNode.isDeleted(), "The door must not be deleted");
                Assertions.assertTrue(newBuilding.getNodes().contains(oldNode));
            } else if (newBuilding.getNodes().contains(oldNode)) {
                Assertions.assertFalse(
                    oldNode.isDeleted(), "A node reused by the new building must not be deleted: " + oldNode
                );
            } else {
                Assertions.assertTrue(oldNode.isDeleted(), "Untagged part node must be deleted: " + oldNode);
            }
        }
        // No way may reference a deleted node (data integrity).
        for (Way way : ds.getWays()) {
            if (way.isDeleted()) {
                continue;
            }
            for (Node node : way.getNodes()) {
                Assertions.assertFalse(node.isDeleted(), "Way references a deleted node: " + way);
            }
        }
    }

    private static Node addDoor(DataSet ds, Way part, LatLon position) {
        Node door = new Node(position);
        door.put("entrance", "main");
        ds.addPrimitive(door);
        List<Node> nodes = new ArrayList<>(part.getNodes());
        nodes.add(nodes.size() - 1, door); // insert before the closing node, on the bottom edge
        part.setNodes(nodes);
        part.setModified(true);
        return door;
    }

    private static boolean isOnOutline(Node node, Way building) {
        for (int i = 0; i < building.getNodesCount() - 1; i++) {
            LatLon closest = BarrierWaysSnapper.closestPointOnSegment(
                node.getCoor(), building.getNode(i).getCoor(), building.getNode(i + 1).getCoor()
            );
            if (BarrierWaysSnapper.distanceSq(node.getCoor(), closest)
                < BarrierWaysSnapper.SAME_POSITION_EPSILON_SQ) {
                return true;
            }
        }
        return false;
    }

    private static Node findNodeWithTag(DataSet ds, String key, String value) {
        return ds.getNodes().stream()
            .filter(node -> node.hasTag(key, value))
            .findFirst()
            .orElse(null);
    }

    private static Way addPart(DataSet ds, String housenumber, LatLon... corners) {
        Way part = new Way();
        part.put("building", "yes");
        part.put("addr:housenumber", housenumber);
        part.put("addr:street", "Testowa");
        for (LatLon corner : corners) {
            Node node = new Node(corner);
            ds.addPrimitive(node);
            part.addNode(node);
        }
        part.addNode(part.getNode(0));
        ds.addPrimitive(part);
        return part;
    }

    private static LatLon latLon(double lat, double lon) {
        return new LatLon(lat, lon);
    }
}
