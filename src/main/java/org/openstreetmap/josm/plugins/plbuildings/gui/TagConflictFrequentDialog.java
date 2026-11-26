package org.openstreetmap.josm.plugins.plbuildings.gui;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.BorderLayout;
import javax.swing.JCheckBox;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import org.openstreetmap.josm.gui.widgets.UrlLabel;

/**
 * Shows a warning dialog when the same remembered tag conflict pair has been automatically resolved many times
 * during one session. It suggests that the import in this area may be of low quality and that it is worth
 * notifying the Polish OSM community on Discord so that the data can be checked and fixed in bulk.
 * The Discord link is clickable and opens in the web browser. The user can decide to not see such warnings
 * again in this session.
 */
public class TagConflictFrequentDialog {

    private static final String DISCORD_URL = "https://discord.gg/96y4wSxG5";

    private TagConflictFrequentDialog() {
    }

    /**
     * @param key conflict tag key
     * @param currentValue tag value of the existing (target) building
     * @param newValue tag value of the imported (source) building
     * @param occurrences number of times the same conflict pair occurred during this session
     * @return true if the user checked the "do not show again" option
     */
    public static boolean show(String key, String currentValue, String newValue, int occurrences) {
        JTextArea messageArea = new JTextArea(tr(
            "The same tag conflict for the \"{0}\" tag (\"{1}\" vs \"{2}\") has already occurred {3} times "
                + "in this session.\n"
                + "This may indicate a low-quality import in this area.\n"
                + "Please consider notifying the Polish OpenStreetMap community on Discord so that the data "
                + "can be checked and fixed in bulk:",
            key,
            currentValue,
            newValue,
            occurrences
        ));
        messageArea.setEditable(false);
        messageArea.setOpaque(false);
        messageArea.setLineWrap(true);
        messageArea.setWrapStyleWord(true);
        messageArea.setColumns(60);

        JCheckBox doNotShowAgainCheckBox = new JCheckBox(tr("Do not show again (this session)."));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(messageArea, BorderLayout.NORTH);
        panel.add(new UrlLabel(DISCORD_URL, DISCORD_URL), BorderLayout.CENTER);
        panel.add(doNotShowAgainCheckBox, BorderLayout.SOUTH);

        JOptionPane.showMessageDialog(
            null,
            panel,
            tr("Repeated tag conflict"),
            JOptionPane.WARNING_MESSAGE
        );
        return doNotShowAgainCheckBox.isSelected();
    }
}
