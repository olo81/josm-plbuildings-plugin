package org.openstreetmap.josm.plugins.plbuildings.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ItemListener;
import java.awt.event.KeyEvent;
import java.util.function.Function;
import javax.swing.BorderFactory;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTextArea;
import javax.swing.border.EmptyBorder;
import org.openstreetmap.josm.gui.dialogs.ToggleDialog;
import org.openstreetmap.josm.plugins.plbuildings.BuildingsPlugin;
import org.openstreetmap.josm.plugins.plbuildings.BuildingsSettings;
import org.openstreetmap.josm.tools.Shortcut;

/**
 * Create sidebar window which contains the latest status of import action and allows to change some cfg.
 */
public class BuildingsToggleDialog extends ToggleDialog {
    private static final int DATA_SOURCE_PROFILE_MAX_CHARS = 20;

    private final JLabel status;

    private final JComboBox<Object> importModeComboBox;
    private final JComboBox<Object> dataSourceProfilesComboBox;

    private final JLabel buildingType;

    private final JLabel buildingLevels;
    private final JLabel hasUncommonTag;

    private final JButton statusReasonButton;
    private final JPopupMenu statusReasonPopup;
    private String fullStatusReason;

    private final JCheckBox dontAttachBarriersCheckBox;

    public BuildingsToggleDialog() {
        super(
            "PlBuildings",
            "plbuildings",
            tr("Open the {0} window", BuildingsPlugin.info.name),
            Shortcut.registerShortcut(
                BuildingsPlugin.info.name + ":window",
                tr("PlBuildings window"),
                KeyEvent.CHAR_UNDEFINED, Shortcut.NONE
            ),
            150
        );

        this.status = new JLabel("");
        this.importModeComboBox = new JComboBox<>();
        this.dataSourceProfilesComboBox = new JComboBox<>();

        this.buildingType = new JLabel("");
        this.buildingLevels = new JLabel("");
        this.hasUncommonTag = new JLabel("");
        this.dontAttachBarriersCheckBox = new JCheckBox(tr("Do not attach fences"));

        this.statusReasonButton = new JButton("ℹ");
        this.statusReasonPopup = new JPopupMenu();
        this.statusReasonButton.setMargin(new Insets(0, 4, 0, 4));
        this.statusReasonButton.setToolTipText(tr("Click to show the reason of the last conflict"));
        this.statusReasonButton.addActionListener(event -> showStatusReasonPopup());
        this.statusReasonButton.setEnabled(false);

        this.dontAttachBarriersCheckBox.setToolTipText(
            tr("Fences are not snapped onto the building outline, cut or deleted during import")
        );
        this.dontAttachBarriersCheckBox.setSelected(BuildingsSettings.DONT_ATTACH_BARRIER_WAYS.get());
        this.dontAttachBarriersCheckBox.addItemListener(event ->
            BuildingsSettings.DONT_ATTACH_BARRIER_WAYS.put(dontAttachBarriersCheckBox.isSelected()));

        final JPanel rootPanel = new JPanel(new GridLayout(0, 1));

        rootPanel.add(createConfigPanel());
        rootPanel.add(createLatestTagsPanel());

        rootPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 300));
        createLayout(rootPanel, true, null);
    }

    private JPanel createConfigPanel() {
        final JPanel configPanel = new JPanel(new GridLayout(0, 1));

        configPanel.add(createRow(new JLabel(tr("Status:")), status));
        configPanel.add(createRow(new JLabel(tr("Reason:")), statusReasonButton));
        configPanel.add(createRow(new JLabel(tr("Import mode:")), importModeComboBox));
        configPanel.add(createRow(new JLabel(tr("Data source:")), dataSourceProfilesComboBox));
        configPanel.add(dontAttachBarriersCheckBox);

        configPanel.setBorder(new EmptyBorder(0, 5, 0, 0));

        return configPanel;
    }

    /**
     * One option per row: the caption on the left, the value next to it.
     */
    private static JPanel createRow(JLabel caption, JComponent value) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 1));
        row.add(caption);
        row.add(value);
        return row;
    }

    private JPanel createLatestTagsPanel() {
        final JPanel lastImportTagsPanel = new JPanel(new GridLayout(0, 2));

        lastImportTagsPanel.add(new JLabel("building:"));
        lastImportTagsPanel.add(buildingType);

        lastImportTagsPanel.add(new JLabel("building:levels:"));
        lastImportTagsPanel.add(buildingLevels);

        lastImportTagsPanel.add(new JLabel(tr("Uncommon tags:")));
        lastImportTagsPanel.add(hasUncommonTag);

        lastImportTagsPanel.setBorder(BorderFactory.createTitledBorder(tr("Latest tags")));

        return lastImportTagsPanel;
    }

    public int getDataSourceProfilesComboBoxSelectedIndex() {
        return dataSourceProfilesComboBox.getSelectedIndex();
    }

    public int getImportModeComboBoxSelectedIndex() {
        return importModeComboBox.getSelectedIndex();
    }

    public void addDataSourceProfilesComboBoxItemListener(ItemListener listener) {
        dataSourceProfilesComboBox.addItemListener(listener);
    }

    public void addImportModeComboBoxItemListener(ItemListener listener) {
        importModeComboBox.addItemListener(listener);
    }

    public void setBuildingTypeText(String buildingTypeText) {
        buildingType.setText(buildingTypeText);
    }

    public void setBuildingLevelsText(String buildingLevelsText) {
        buildingLevels.setText(buildingLevelsText);
    }

    public void setHasUncommonTagText(String hasUncommonTagText) {
        hasUncommonTag.setText(hasUncommonTagText);
    }

    public void setStatusText(String statusText) {
        status.setText(statusText);
    }

    public void setStatusReasonText(String statusReasonText) {
        // The button stays tiny – the full reason is shown in a floating bubble after clicking it,
        // so the side panel never changes its size.
        this.fullStatusReason = statusReasonText;
        statusReasonButton.setEnabled(statusReasonText != null && !statusReasonText.isEmpty());
    }

    private void showStatusReasonPopup() {
        if (fullStatusReason == null || fullStatusReason.isEmpty()) {
            return;
        }
        statusReasonPopup.removeAll();
        JTextArea messageArea = new JTextArea(fullStatusReason);
        messageArea.setEditable(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setColumns(40);
        messageArea.setBorder(new EmptyBorder(8, 8, 8, 8));
        statusReasonPopup.add(messageArea);
        statusReasonPopup.show(statusReasonButton, 0, statusReasonButton.getHeight());
    }

    public void setBuildingTypeForeground(Color color) {
        buildingType.setForeground(color);
    }

    public void setHasUncommonTagForeground(Color color) {
        hasUncommonTag.setForeground(color);
    }

    public void setStatusForeground(Color color) {
        status.setForeground(color);
    }

    public void setDataSourceProfilesComboBoxModel(ComboBoxModel<Object> model) {
        dataSourceProfilesComboBox.setModel(model);
    }

    public void setImportModeComboBoxModel(ComboBoxModel<Object> model) {
        importModeComboBox.setModel(model);
    }

    public void setDataSourceProfilesComboBoxSelectedIndex(int index) {
        dataSourceProfilesComboBox.setSelectedIndex(index);
    }

    public void setDataSourceProfilesComboBoxRenderer(Function<Object, String> getValueFromModelObject) {
        dataSourceProfilesComboBox.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                          boolean isSelected, boolean cellHasFocus) {
                super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value == null) {
                    return this;
                }
                String profileName = getValueFromModelObject.apply(value);
                setText(profileName.substring(0,
                    Math.min(DATA_SOURCE_PROFILE_MAX_CHARS, profileName.length())));
                return this;
            }
        });
    }

}
