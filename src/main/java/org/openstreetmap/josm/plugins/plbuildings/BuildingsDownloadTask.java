package org.openstreetmap.josm.plugins.plbuildings;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.util.concurrent.ExecutionException;
import javax.swing.SwingWorker;
import org.openstreetmap.josm.plugins.plbuildings.enums.ImportStatus;
import org.openstreetmap.josm.plugins.plbuildings.io.BuildingsDownloader;
import org.openstreetmap.josm.plugins.plbuildings.models.BuildingsImportData;
import org.openstreetmap.josm.tools.Logging;

/**
 * responsible for running SwingWorker for doing background (downloading) task to avoid freezing GUI.
 */
public class BuildingsDownloadTask extends SwingWorker<BuildingsImportData, Object> {
    private final BuildingsImportManager buildingsImportManager;

    public BuildingsDownloadTask(BuildingsImportManager buildingsImportManager) {
        this.buildingsImportManager = buildingsImportManager;
    }

    @Override
    protected BuildingsImportData doInBackground() {
        return BuildingsDownloader.getBuildingsImportData(buildingsImportManager);
    }

    @Override
    protected void done() {
        try {
            this.buildingsImportManager.setImportedData(get());
            this.buildingsImportManager.processDownloadedData();
        } catch (InterruptedException e) {
            Logging.error("PlBuildings import task interrupted: {0}", e.getMessage());
            Thread.currentThread().interrupt();
            buildingsImportManager.setStatus(ImportStatus.IMPORT_ERROR, tr("Building import interrupted!"));
        } catch (ExecutionException e) {
            // Without this the failure is silent – the user would have to click again with no idea
            // what happened (e.g. a temporary server/download error).
            Throwable cause = e.getCause() == null ? e : e.getCause();
            Logging.error("PlBuildings runtime error at SwingWorker BuildingImportTask: {0}", cause);
            buildingsImportManager.setStatus(
                ImportStatus.IMPORT_ERROR,
                tr("Cannot download or import the building!") + " (" + cause.getMessage() + ")"
            );
        }
    }
}
