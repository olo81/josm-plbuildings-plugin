package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.plugins.plbuildings.ImportUtils.importOsmFile;
import static org.openstreetmap.josm.plugins.plbuildings.validators.BuildingsWayValidator.isBuildingWayValid;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import mockit.Mock;
import mockit.MockUp;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.command.SequenceCommand;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.data.validation.tests.DuplicateNode;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.plugins.plbuildings.commands.AddBuildingGeometryCommand;
import org.openstreetmap.josm.plugins.plbuildings.commands.ReplaceBuildingGeometryCommand;
import org.openstreetmap.josm.plugins.plbuildings.commands.UpdateBuildingTagsCommand;
import org.openstreetmap.josm.testutils.JOSMTestRules;
import org.openstreetmap.josm.tools.UserCancelException;

/**
 * Checks that a failed import (e.g. the user cancels the tag conflict dialog) rolls the dataset back
 * completely: the building keeps its original geometry and no way is left referencing a node which is
 * no longer part of the dataset.
 */
public class FailedImportRollbackTest {

    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main().projection();

    @Test
    void testCanceledTagConflictLeavesDatasetConsistent() {
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
        int originalWayCount = ds.getWays().size();
        int originalNodeCount = ds.getNodes().size();

        // Simulate the user closing the tag conflict dialog.
        new MockUp<UpdateBuildingTagsCommand>() {
            @Mock
            public List<Command> prepareUpdateTagsCommands(
                Way selectedBuilding,
                Way newBuilding
            ) throws UserCancelException {
                throw new UserCancelException();
            }
        };

        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding);
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(
            ds, selectedBuilding, addCommand
        );
        UpdateBuildingTagsCommand updateCommand = new UpdateBuildingTagsCommand(
            ds, () -> selectedBuilding, importedBuilding, true
        );
        SequenceCommand sequence = new SequenceCommand(
            "test import", Arrays.asList(addCommand, replaceCommand, updateCommand)
        );
        Assertions.assertFalse(sequence.executeCommand());

        // The building must keep its original geometry (no import nodes left in the way).
        Assertions.assertEquals(originalNodes, selectedBuilding.getNodes());
        Assertions.assertTrue(isBuildingWayValid(selectedBuilding));

        // The temporary imported building and all its new nodes must be removed from the dataset.
        Way temporaryBuilding = addCommand.getResultBuilding();
        Assertions.assertNull(temporaryBuilding.getDataSet());
        for (Node node : temporaryBuilding.getNodes()) {
            if (node.getId() < 0) {
                Assertions.assertNull(node.getDataSet(), "new import node " + node.getId());
            }
        }

        // No way may reference a node which is not part of the dataset (this used to crash the
        // validator with "Primitive must be part of the dataset").
        for (Way way : ds.getWays()) {
            for (Node node : way.getNodes()) {
                Assertions.assertSame(
                    ds, node.getDataSet(), "node " + node.getId() + " referenced by way " + way.getId()
                );
            }
        }

        // The failed import must not leave any extra primitives behind.
        Assertions.assertEquals(originalWayCount, ds.getWays().size());
        Assertions.assertEquals(originalNodeCount, ds.getNodes().size());

        // The duplicate node validator must not report anything after the rollback.
        Assertions.assertTrue(findDuplicateNodeErrors(ds).isEmpty());
    }

    @Test
    void testFillModifiedDataDoesNotModifyDataset() {
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

        AddBuildingGeometryCommand addCommand = new AddBuildingGeometryCommand(ds, importedBuilding);
        addCommand.executeCommand();
        ReplaceBuildingGeometryCommand replaceCommand = new ReplaceBuildingGeometryCommand(
            ds, selectedBuilding, addCommand
        );
        Assertions.assertTrue(replaceCommand.executeCommand());

        List<Node> nodesAfterReplace = new ArrayList<>(selectedBuilding.getNodes());
        int wayCountAfterReplace = ds.getWays().size();

        // fillModifiedData is called by the UndoRedoHandler for conflict detection and must only
        // report data – it must not execute the geometry replacement again.
        Collection<OsmPrimitive> modified = new ArrayList<>();
        Collection<OsmPrimitive> deleted = new ArrayList<>();
        Collection<OsmPrimitive> added = new ArrayList<>();
        replaceCommand.fillModifiedData(modified, deleted, added);

        Assertions.assertEquals(nodesAfterReplace, selectedBuilding.getNodes());
        Assertions.assertEquals(wayCountAfterReplace, ds.getWays().size());
        Assertions.assertTrue(modified.contains(selectedBuilding));
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
