package org.openstreetmap.josm.plugins.plbuildings.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import org.openstreetmap.josm.command.Command;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.TagCollection;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.gui.conflict.tags.CombinePrimitiveResolver;
import org.openstreetmap.josm.gui.conflict.tags.CombinePrimitiveResolverDialog;
import org.openstreetmap.josm.gui.conflict.tags.MultiValueDecisionType;
import org.openstreetmap.josm.gui.conflict.tags.MultiValueResolutionDecision;
import org.openstreetmap.josm.gui.conflict.tags.RelationMemberConflictResolverModel;
import org.openstreetmap.josm.gui.conflict.tags.TagConflictResolutionUtil;
import org.openstreetmap.josm.gui.conflict.tags.TagConflictResolverModel;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.plugins.plbuildings.utils.BuildingsSessionStateManager;
import org.openstreetmap.josm.plugins.plbuildings.utils.TagConflictResolution;
import org.openstreetmap.josm.tools.CheckParameterUtil;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.UserCancelException;

/**
 * Extends the original JOSM tag conflict resolution dialog with a checkbox column on the right side
 * of the conflicting tag rows. Checked pairs are remembered for the rest of the session: the next time
 * the same conflict occurs, the remembered value is applied automatically and the dialog doesn't appear
 * again until JOSM is restarted.
 */
public class PlBuildingsConflictResolverDialog extends CombinePrimitiveResolverDialog {
    private static final int FREQUENT_TAG_CONFLICT_THRESHOLD = 10;

    /**
     * Guards against showing two conflict dialogs at the same time. When the user triggers another
     * import while the dialog is open, the nested AWT event pump would otherwise show a second
     * dialog on top of the first one.
     */
    private static final AtomicBoolean DIALOG_OPEN = new AtomicBoolean(false);

    private final Way selectedBuilding;
    private final Way newBuilding;
    private Map<String, Boolean> rememberStates;
    private Set<String> realConflictKeys;

    /**
     * @param tagModel model with all tag conflicts to resolve
     * @param relModel model with all relation membership conflicts to resolve
     * @param selectedBuilding the existing building with current tags
     * @param newBuilding the imported building with new tags
     */
    public PlBuildingsConflictResolverDialog(
        TagConflictResolverModel tagModel,
        RelationMemberConflictResolverModel relModel,
        Way selectedBuilding,
        Way newBuilding
    ) {
        super(null, tagModel, relModel);
        this.selectedBuilding = selectedBuilding;
        this.newBuilding = newBuilding;
    }

    @Override
    protected JPanel buildTagConflictResolverPanel() {
        JPanel resolverPanel = super.buildTagConflictResolverPanel();
        JScrollPane scrollPane = (JScrollPane) ((BorderLayout) resolverPanel.getLayout())
            .getLayoutComponent(BorderLayout.CENTER);
        JTable table = (JTable) scrollPane.getViewport().getView();
        TableColumn rememberColumn = new TableColumn(table.getColumnCount());
        rememberColumn.setHeaderValue(tr("Remember"));
        rememberColumn.setPreferredWidth(90);
        rememberColumn.setMaxWidth(130);
        rememberStates = new HashMap<>();
        realConflictKeys = new HashSet<>();
        // Only real conflicts (pairs requiring the user's decision) get the checkbox. Pairs already
        // auto-resolved by default decisions (e.g. floors present only on OSM) don't need to be remembered.
        getTagConflictResolverModel().actOnDecisions((key, decision) -> {
            if (!decision.isDecided()) {
                realConflictKeys.add(key);
            }
        });
        rememberColumn.setCellRenderer(createRememberCellRenderer());
        table.addColumn(rememberColumn);
        int rememberColumnViewIndex = table.convertColumnIndexToView(rememberColumn.getModelIndex());
        table.addMouseListener(createRememberColumnMouseListener(table, rememberColumnViewIndex));
        return resolverPanel;
    }

    /**
     * Stores the user's resolutions of the checked conflict pairs from this dialog in the session state.
     * Each conflict pair (key, current value, new value) is remembered separately.
     */
    public void rememberChosenValues() {
        getTagConflictResolverModel().actOnDecisions((key, decision) -> {
            if (!decision.isDecided() || !rememberStates.getOrDefault(key, false)) {
                return;
            }
            String currentValue = selectedBuilding.get(key);
            String newValue = newBuilding.get(key);
            if (currentValue == null || newValue == null || currentValue.equals(newValue)) {
                return;
            }
            BuildingsSessionStateManager.rememberTagConflictResolution(
                key,
                currentValue,
                newValue,
                new TagConflictResolution(decision.getDecisionType(), decision.getChosenValue())
            );
        });
    }

    private TableCellRenderer createRememberCellRenderer() {
        return (table, value, isSelected, hasFocus, row, column) -> {
            JCheckBox checkBox = new JCheckBox();
            checkBox.setOpaque(true);
            checkBox.setHorizontalAlignment(SwingConstants.CENTER);
            checkBox.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            checkBox.setToolTipText(tr("Remember this choice until the end of the session"));
            if (value instanceof MultiValueResolutionDecision) {
                String key = ((MultiValueResolutionDecision) value).getKey();
                boolean isConflict = realConflictKeys.contains(key);
                checkBox.setEnabled(isConflict);
                checkBox.setSelected(isConflict && rememberStates.getOrDefault(key, false));
            } else {
                checkBox.setEnabled(false);
            }
            return checkBox;
        };
    }

    private MouseAdapter createRememberColumnMouseListener(JTable table, int rememberColumnViewIndex) {
        return new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (table.columnAtPoint(event.getPoint()) != rememberColumnViewIndex) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                if (row < 0) {
                    return;
                }
                Object value = table.getValueAt(row, rememberColumnViewIndex);
                if (!(value instanceof MultiValueResolutionDecision)) {
                    return;
                }
                String key = ((MultiValueResolutionDecision) value).getKey();
                if (!realConflictKeys.contains(key)) {
                    return;
                }
                rememberStates.put(key, !rememberStates.getOrDefault(key, false));
                table.repaint();
            }
        };
    }

    /**
     * @return true when a conflict resolution dialog is currently open
     */
    public static boolean isConflictDialogOpen() {
        return DIALOG_OPEN.get();
    }

    /**
     * Works like {@link CombinePrimitiveResolverDialog#launchIfNecessary(TagCollection, Collection, Collection)},
     * but conflicts with a remembered resolution are resolved automatically (without showing the dialog)
     * and the dialog itself has a checkbox next to each conflicting tag pair.
     *
     * @param tagsOfPrimitives tags of both primitives (can be modified)
     * @param primitives primitives with tags in conflict (selected and imported building)
     * @param targetPrimitives primitives to which the resolutions will be applied
     * @param selectedBuilding the existing building with current tags
     * @param newBuilding the imported building with new tags
     * @return commands with conflict resolutions
     * @throws UserCancelException if the user cancels the conflict resolution dialog
     */
    public static List<Command> launchIfNecessary(
        TagCollection tagsOfPrimitives,
        Collection<? extends OsmPrimitive> primitives,
        Collection<? extends OsmPrimitive> targetPrimitives,
        Way selectedBuilding,
        Way newBuilding
    ) throws UserCancelException {
        CheckParameterUtil.ensureParameterNotNull(tagsOfPrimitives, "tagsOfPrimitives");
        CheckParameterUtil.ensureParameterNotNull(primitives, "primitives");
        CheckParameterUtil.ensureParameterNotNull(targetPrimitives, "targetPrimitives");

        TagCollection tagsToEdit = new TagCollection(tagsOfPrimitives);
        TagConflictResolutionUtil.applyAutomaticTagConflictResolution(tagsToEdit);
        TagConflictResolutionUtil.normalizeTagCollectionBeforeEditing(tagsToEdit, primitives);
        TagCollection tagsToEditForDialog = new TagCollection(tagsToEdit);
        TagConflictResolutionUtil.completeTagCollectionForEditing(tagsToEditForDialog);

        Set<Relation> relations = OsmPrimitive.getParentRelations(primitives);

        TagConflictResolverModel tagModel = new TagConflictResolverModel();
        RelationMemberConflictResolverModel relModel = new RelationMemberConflictResolverModel();
        tagModel.populate(tagsToEditForDialog, tagsToEdit.getKeysWithMultipleValues());
        relModel.populate(relations, primitives);
        tagModel.prepareDefaultTagDecisions();
        relModel.prepareDefaultRelationDecisions();
        applyRememberedResolutions(tagModel, selectedBuilding, newBuilding);

        List<Command> commands = new LinkedList<>();
        if (tagModel.isResolvedCompletely() && relModel.isResolvedCompletely()) {
            // No conflict dialog will be shown – the warning about repeated remembered conflicts
            // is shown alone (never two windows at the same time).
            showFrequentConflictWarnings(tagModel, selectedBuilding, newBuilding);
            return buildResolutionCommands(tagModel, relModel, targetPrimitives, commands);
        }
        Logging.info(
            "PlBuildings conflict dialog about to show: tag conflicts {0}, relation decisions {1}",
            tagsToEdit.getKeysWithMultipleValues(),
            relModel.getNumDecisions()
        );
        if (GraphicsEnvironment.isHeadless()) {
            return commands;
        }
        if (!DIALOG_OPEN.compareAndSet(false, true)) {
            // A conflict dialog is already open – never stack a second dialog on top of it.
            Logging.warn("PlBuildings conflict dialog skipped: another conflict dialog is already open.");
            throw new UserCancelException(tr("Conflict resolution canceled"));
        }
        try {
            UserCancelException canceled = GuiHelper.runInEDTAndWaitAndReturn(() -> {
                PlBuildingsConflictResolverDialog dialog = new PlBuildingsConflictResolverDialog(
                    tagModel,
                    relModel,
                    selectedBuilding,
                    newBuilding
                );
                dialog.setModalityType(Dialog.ModalityType.APPLICATION_MODAL);
                dialog.prepareGUIBeforeConflictResolutionStarts();
                dialog.setVisible(true);
                try {
                    if (dialog.isApplied()) {
                        dialog.rememberChosenValues();
                        buildResolutionCommands(tagModel, relModel, targetPrimitives, commands);
                        return null;
                    }
                    return new UserCancelException(tr("Conflict resolution canceled"));
                } finally {
                    dialog.dispose();
                }
            });
            if (canceled != null) {
                throw canceled;
            }
            return commands;
        } finally {
            DIALOG_OPEN.set(false);
        }
    }

    private static List<Command> buildResolutionCommands(
        TagConflictResolverModel tagModel,
        RelationMemberConflictResolverModel relModel,
        Collection<? extends OsmPrimitive> targetPrimitives,
        List<Command> commands
    ) {
        CombinePrimitiveResolver resolver = new CombinePrimitiveResolver(tagModel, relModel);
        for (OsmPrimitive target : targetPrimitives) {
            commands.addAll(resolver.buildResolutionCommands(target));
        }
        return commands;
    }

    private static void applyRememberedResolutions(
        TagConflictResolverModel tagModel,
        Way selectedBuilding,
        Way newBuilding
    ) {
        tagModel.actOnDecisions((key, decision) -> {
            String currentValue = selectedBuilding.get(key);
            String newValue = newBuilding.get(key);
            if (currentValue == null || newValue == null || currentValue.equals(newValue)) {
                return;
            }
            TagConflictResolution resolution = BuildingsSessionStateManager.getRememberedTagConflictResolution(
                key,
                currentValue,
                newValue
            );
            if (resolution != null) {
                applyResolution(decision, resolution);
                BuildingsSessionStateManager.registerTagConflictOccurrence(key, currentValue, newValue);
            }
        });
        // Applying decisions does not refresh the model's conflict counter – rebuild it so that
        // isResolvedCompletely() sees the remembered resolutions.
        tagModel.rebuild();
    }

    /**
     * Shows the warning about a remembered conflict pair which has already occurred many times in this session.
     * Only one warning is shown per import (even if several conflict pairs reached the threshold) and it can be
     * silenced for the rest of the session.
     */
    private static void showFrequentConflictWarnings(
        TagConflictResolverModel tagModel,
        Way selectedBuilding,
        Way newBuilding
    ) {
        if (BuildingsSessionStateManager.isTagConflictFrequentWarningsSuppressed()
            || GraphicsEnvironment.isHeadless()) {
            return;
        }
        AtomicBoolean warningShown = new AtomicBoolean(false);
        tagModel.actOnDecisions((key, decision) -> {
            if (warningShown.get()) {
                return;
            }
            String currentValue = selectedBuilding.get(key);
            String newValue = newBuilding.get(key);
            if (currentValue == null || newValue == null || currentValue.equals(newValue)) {
                return;
            }
            if (BuildingsSessionStateManager.isFrequentTagConflictWarningShown(key, currentValue, newValue)) {
                return;
            }
            int occurrences = BuildingsSessionStateManager.getTagConflictOccurrences(key, currentValue, newValue);
            if (occurrences < FREQUENT_TAG_CONFLICT_THRESHOLD) {
                return;
            }
            boolean doNotShowAgain = TagConflictFrequentDialog.show(key, currentValue, newValue, occurrences);
            warningShown.set(true);
            BuildingsSessionStateManager.markFrequentTagConflictWarningShown(key, currentValue, newValue);
            if (doNotShowAgain) {
                BuildingsSessionStateManager.suppressTagConflictFrequentWarnings();
            }
        });
    }

    private static void applyResolution(
        MultiValueResolutionDecision decision,
        TagConflictResolution resolution
    ) {
        MultiValueDecisionType decisionType = resolution.getDecisionType();
        if (decisionType == MultiValueDecisionType.KEEP_NONE) {
            decision.keepNone();
        } else if (decisionType == MultiValueDecisionType.KEEP_ALL) {
            decision.keepAll();
        } else if (decisionType == MultiValueDecisionType.SUM_ALL_NUMERIC) {
            decision.sumAllNumeric();
        } else if (decisionType == MultiValueDecisionType.KEEP_ONE && resolution.getChosenValue() != null) {
            decision.keepOne(resolution.getChosenValue());
        }
    }
}
