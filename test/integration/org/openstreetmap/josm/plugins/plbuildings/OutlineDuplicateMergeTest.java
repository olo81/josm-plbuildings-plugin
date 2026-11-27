package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.plugins.plbuildings.utils.BarrierWaysSnapper;
import org.openstreetmap.josm.testutils.JOSMTestRules;

/**
 * Checks that merging duplicate outline nodes keeps closed ways closed, even when the merged node
 * is the closing (first/last) node of the way.
 */
public class OutlineDuplicateMergeTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @Test
    void testMergingClosingNodeKeepsWayClosed() {
        DataSet ds = new DataSet();
        Way building = new Way(1234, 1);
        building.put("building", "yes");
        Node a = new Node(new LatLon(0, 0));
        Node b = new Node(new LatLon(0, 0.001));
        Node c = new Node(new LatLon(0.001, 0.001));
        // A tagged node at the same position as the closing node – the tagged node becomes the survivor.
        Node survivor = new Node(new LatLon(0, 0));
        survivor.put("entrance", "main");
        for (Node node : Arrays.asList(a, b, c, survivor)) {
            ds.addPrimitive(node);
        }
        building.setNodes(Arrays.asList(a, b, c, survivor, a));
        ds.addPrimitive(building);

        List<Command> commands = new ArrayList<>();
        List<OsmPrimitive> touched = new ArrayList<>();
        BarrierWaysSnapper.mergeDuplicateOutlineNodes(ds, building, commands, touched);

        // The way stays closed and valid, with the survivor instead of the merged node.
        Assertions.assertTrue(isBuildingWayValid(building), "The building way must stay valid");
        Assertions.assertTrue(building.isClosed());
        Assertions.assertTrue(building.containsNode(survivor));
        Assertions.assertFalse(building.containsNode(a));
        Assertions.assertTrue(a.isDeleted());
    }
}
