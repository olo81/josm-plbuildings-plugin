package org.openstreetmap.josm.plugins.plbuildings.utils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.openstreetmap.josm.actions.ExpertToggleAction;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.TagCollection;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.conflict.tags.MultiValueDecisionType;
import org.openstreetmap.josm.plugins.plbuildings.gui.PlBuildingsConflictResolverDialog;
import org.openstreetmap.josm.testutils.JOSMTestRules;
import org.openstreetmap.josm.tools.UserCancelException;

public class TagConflictResolutionTest {
    @RegisterExtension
    static JOSMTestRules rule = new JOSMTestRules().main();

    @BeforeEach
    void setUp() {
        ExpertToggleAction.getInstance().setExpert(true);
        BuildingsSessionStateManager.clearTagConflictSessionState();
    }

    @Test
    void testRememberedResolutionIsStoredPerConflictPair() {
        TagConflictResolution resolution = new TagConflictResolution(MultiValueDecisionType.KEEP_ONE, "outbuilding");
        BuildingsSessionStateManager.rememberTagConflictResolution(
            "building", "manufacture", "outbuilding", resolution
        );

        Assertions.assertEquals(
            resolution,
            BuildingsSessionStateManager.getRememberedTagConflictResolution("building", "manufacture", "outbuilding")
        );
        Assertions.assertNull(
            BuildingsSessionStateManager.getRememberedTagConflictResolution("building", "manufacture", "retail")
        );
    }

    @Test
    void testTagConflictOccurrencesAreCountedPerConflictPair() {
        for (int i = 1; i <= 10; i++) {
            Assertions.assertEquals(
                i,
                BuildingsSessionStateManager.registerTagConflictOccurrence("building", "manufacture", "outbuilding")
            );
        }
        Assertions.assertEquals(
            1,
            BuildingsSessionStateManager.registerTagConflictOccurrence("building", "manufacture", "retail")
        );
    }

    @Test
    void testSessionStateCanBeCleared() {
        BuildingsSessionStateManager.rememberTagConflictResolution(
            "building", "manufacture", "outbuilding",
            new TagConflictResolution(MultiValueDecisionType.KEEP_NONE, null)
        );
        BuildingsSessionStateManager.registerTagConflictOccurrence("building", "manufacture", "outbuilding");

        BuildingsSessionStateManager.clearTagConflictSessionState();

        Assertions.assertNull(
            BuildingsSessionStateManager.getRememberedTagConflictResolution("building", "manufacture", "outbuilding")
        );
        Assertions.assertEquals(
            1,
            BuildingsSessionStateManager.registerTagConflictOccurrence("building", "manufacture", "outbuilding")
        );
    }

    @Test
    void testFrequentWarningShownStateIsTrackedPerConflictPair() {
        Assertions.assertFalse(
            BuildingsSessionStateManager.isFrequentTagConflictWarningShown("building", "manufacture", "outbuilding")
        );
        BuildingsSessionStateManager.markFrequentTagConflictWarningShown("building", "manufacture", "outbuilding");

        Assertions.assertTrue(
            BuildingsSessionStateManager.isFrequentTagConflictWarningShown("building", "manufacture", "outbuilding")
        );
        Assertions.assertFalse(
            BuildingsSessionStateManager.isFrequentTagConflictWarningShown("building", "manufacture", "retail")
        );
    }

    @Test
    void testFrequentWarningsCanBeSuppressedForTheSession() {
        Assertions.assertFalse(BuildingsSessionStateManager.isTagConflictFrequentWarningsSuppressed());

        BuildingsSessionStateManager.suppressTagConflictFrequentWarnings();

        Assertions.assertTrue(BuildingsSessionStateManager.isTagConflictFrequentWarningsSuppressed());

        BuildingsSessionStateManager.clearTagConflictSessionState();
        Assertions.assertFalse(BuildingsSessionStateManager.isTagConflictFrequentWarningsSuppressed());
    }

    @Test
    void testRememberedResolutionIsAppliedWithoutShowingDialog() throws UserCancelException {
        DataSet ds = new DataSet();
        Way selectedBuilding = new Way();
        selectedBuilding.put("building", "manufacture");
        ds.addPrimitiveRecursive(selectedBuilding);

        DataSet newDs = new DataSet();
        Way newBuilding = new Way();
        newBuilding.put("building", "outbuilding");
        newDs.addPrimitiveRecursive(newBuilding);

        BuildingsSessionStateManager.rememberTagConflictResolution(
            "building", "manufacture", "outbuilding",
            new TagConflictResolution(MultiValueDecisionType.KEEP_ONE, "outbuilding")
        );

        TagCollection tags = TagCollection.unionOfAllPrimitives(Arrays.asList(selectedBuilding, newBuilding));
        List<Command> commands = PlBuildingsConflictResolverDialog.launchIfNecessary(
            tags,
            Arrays.asList(selectedBuilding, newBuilding),
            Collections.singletonList(selectedBuilding),
            selectedBuilding,
            newBuilding
        );

        Assertions.assertFalse(commands.isEmpty());
        commands.forEach(Command::executeCommand);
        Assertions.assertTrue(selectedBuilding.hasTag("building", "outbuilding"));
    }
}
