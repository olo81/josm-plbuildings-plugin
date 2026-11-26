package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.ImportUtils.importOsmFile;
import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.plbuildings.commands.AddBuildingGeometryCommand;
import org.openstreetmap.josm.plugins.plbuildings.commands.ReplaceBuildingGeometryCommand;
import org.openstreetmap.josm.testutils.JOSMTestRules;

/**
 * Checks that rolling back a geometry replacement (e.g. when the user cancels the tag conflict
 * dialog) always leaves a consistent dataset: no way may reference a deleted node and the original
 * building geometry is restored.
 */
public class CanceledImportRollbackTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @Test
    void testRollbackAfterGeometryReplacementLeavesConsistentDataset() {
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
        List<Node> originalNodes = new ArrayList<>(selectedBuilding.getNodes());

        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding);
        addCommand.executeCommand();
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(
            ds, selectedBuilding, addCommand
        );
        Assertions.assertTrue(replaceCommand.executeCommand());

        // Simulate the rollback triggered by canceling the tag conflict dialog.
        replaceCommand.undoCommand();
        addCommand.undoCommand();

        // The original geometry is restored.
        Assertions.assertEquals(originalNodes, selectedBuilding.getNodes());
        Assertions.assertTrue(isBuildingWayValid(selectedBuilding));

        // No way references a deleted node – the dataset is consistent.
        for (Way way : ds.getWays()) {
            if (way.isDeleted()) {
                continue;
            }
            for (Node node : way.getNodes()) {
                Assertions.assertFalse(node.isDeleted(), "Way references a deleted node: " + way);
            }
        }
    }

    @Test
    void testRollbackRestoresDeletedNodesStillReferencedByWays() {
        DataSet ds = importOsmFile(new File("test/data/sticky_nodes/current_building.osm"), "");
        Assertions.assertNotNull(ds);
        MainApplication.getLayerManager().addLayer(new OsmDataLayer(ds, "test", null));

        Way selectedBuilding = ds.getWays().stream()
            .filter(way -> way.hasTag("building"))
            .findFirst()
            .orElseThrow();
        // Fabricate the corrupted state which a broken rollback used to leave behind: a way which
        // still references a deleted node.
        Node corruptedNode = selectedBuilding.getNode(1);
        corruptedNode.setDeleted(true);

        // The rollback of the replacement command must heal this state.
        ReplaceBuildingGeometryCommand.replaceReferencedDeletedNodes(ds);
        Assertions.assertFalse(corruptedNode.isDeleted());
        for (Way way : ds.getWays()) {
            if (way.isDeleted()) {
                continue;
            }
            for (Node node : way.getNodes()) {
                Assertions.assertFalse(node.isDeleted(), "Way references a deleted node: " + way);
            }
        }
    }
}
